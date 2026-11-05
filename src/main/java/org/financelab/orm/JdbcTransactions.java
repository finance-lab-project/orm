package org.financelab.orm;

import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.util.PSQLException;

import java.sql.*;
import java.util.*;

/** Synchronous callback scopes over a root-owned Hikari pool. No callback or SQL retries. */
public final class JdbcTransactions implements Transactions {
    private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();
    private final Pool pool;

    public JdbcTransactions(HikariDataSource dataSource) {
        Objects.requireNonNull(dataSource);
        if (!dataSource.isAutoCommit()) throw new IllegalArgumentException("Pool baseline must use autocommit");
        pool = new Pool() {
            public Connection borrow() throws SQLException { return dataSource.getConnection(); }
            public void release(Connection c) throws SQLException { c.close(); }
            public void discard(Connection c) throws SQLException {
                try {
                    dataSource.evictConnection(c);
                } catch (RuntimeException eviction) {
                    // Never return an unresolved transaction if pool eviction itself failed.
                    try { c.abort(Runnable::run); } catch (Throwable abort) {
                        eviction.addSuppressed(abort);
                        throw eviction;
                    }
                    try { c.close(); } catch (Throwable close) { eviction.addSuppressed(close); }
                    throw eviction;
                }
                c.close();
            }
        };
    }

    // Test seam wraps actual pool borrows, including faults outside the pool's proxy.
    interface Pool {
        Connection borrow() throws SQLException;
        void release(Connection connection) throws SQLException;
        void discard(Connection connection) throws SQLException;
    }
    JdbcTransactions(Pool pool) { this.pool = Objects.requireNonNull(pool); }

    @Override public <R> Completed<R> write(Work<WriteSession, R> work) {
        return run(false, work);
    }
    @Override public <R> Completed<R> snapshot(Work<ReadSession, R> work) {
        Objects.requireNonNull(work);
        return run(true, s -> work.run(s::query));
    }

    private <R> Completed<R> run(boolean snapshot, Work<WriteSession, R> work) {
        Session outer = ACTIVE.get();
        if (outer != null) throw outer.failed(new IllegalStateException("Nested transaction scopes are forbidden"));
        Objects.requireNonNull(work);
        Connection connection = null;
        Baseline baseline = null;
        Session session = null;
        boolean ended = false;
        boolean commitStarted = false;
        boolean confirmed = false;
        boolean snapshotFinishStarted = false;
        boolean discard = false;
        R value = null;
        Throwable failure = null;
        WriteOutcome outcome = WriteOutcome.NOT_COMMITTED;
        List<Throwable> cleanup = new ArrayList<>();
        try {
            connection = pool.borrow();
            baseline = new Baseline(connection.getAutoCommit(), connection.isReadOnly(), connection.getTransactionIsolation());
            if (!baseline.autoCommit()) throw new IllegalStateException("Borrowed connection has no clean autocommit baseline");
            connection.setReadOnly(snapshot);
            connection.setTransactionIsolation(snapshot ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try (PreparedStatement utc = connection.prepareStatement("SET LOCAL TIME ZONE 'UTC'")) { utc.execute(); }
            session = new Session(connection);
            ACTIVE.set(session);
            try { value = work.run(session); }
            finally { session.active = false; ACTIVE.remove(); }
            if (session.firstFailure != null) throw session.firstFailure;
            if (snapshot) {
                snapshotFinishStarted = true;
                connection.rollback();
                ended = true;
            } else {
                commitStarted = true;
                connection.commit();
                confirmed = true;
                ended = true;
            }
        } catch (Throwable caught) {
            failure = caught;
            if (!(caught instanceof Error) && session != null && session.firstFailure != null) {
                failure = session.firstFailure;
                if (caught != failure && !(caught instanceof PersistenceFailure && caught.getCause() == failure))
                    failure.addSuppressed(caught);
            }
            if (commitStarted && !confirmed && !serverRejectedCommit(caught)) {
                outcome = WriteOutcome.UNKNOWN;
                discard = true;
            }
            if (snapshotFinishStarted && !ended) discard = true;
            if (connection != null && !ended && !snapshotFinishStarted) {
                try { connection.rollback(); ended = true; }
                catch (Throwable rollback) { cleanup.add(rollback); discard = true; }
            }
        } finally {
            if (session != null) session.active = false;
            ACTIVE.remove();
        }

        if (connection != null) {
            if (!ended || baseline == null) discard = true;
            if (!discard) {
                try {
                    if (!connection.isValid(2)) throw new SQLException("Borrowed connection is no longer healthy", "08006");
                    // Autocommit is enabled only after explicit confirmed completion.
                    connection.setReadOnly(baseline.readOnly());
                    connection.setTransactionIsolation(baseline.isolation());
                    connection.setAutoCommit(baseline.autoCommit());
                    pool.release(connection);
                } catch (Throwable resetOrClose) { cleanup.add(resetOrClose); discard = true; }
            }
            if (discard) {
                try { pool.discard(connection); }
                catch (Throwable eviction) { cleanup.add(eviction); }
            }
        }
        if (snapshot && failure == null && !cleanup.isEmpty()) failure = cleanup.removeFirst();
        if (failure != null) {
            for (Throwable later : cleanup) if (later != failure) failure.addSuppressed(later);
            if (failure instanceof Error fatal) throw fatal;
            throw new PersistenceFailure(outcome, failure);
        }
        // Once commit is confirmed, cleanup cannot turn the result into a failed save.
        return new Completed<>(value, cleanup);
    }

    private static boolean serverRejectedCommit(Throwable failure) {
        if (!(failure instanceof PSQLException pg) || pg.getServerErrorMessage() == null) return false;
        String state = pg.getSQLState();
        return state != null && (state.startsWith("23") || Set.of("40001", "40P01", "25P02").contains(state));
    }

    private record Baseline(boolean autoCommit, boolean readOnly, int isolation) {}

    private static final class Session implements WriteSession {
        private final Connection connection;
        private final Thread owner = Thread.currentThread();
        private volatile boolean active = true;
        private volatile Throwable firstFailure;
        Session(Connection connection) { this.connection = connection; }

        synchronized PersistenceFailure failed(Throwable cause) {
            if (firstFailure == null) firstFailure = cause;
            if (cause instanceof Error fatal) throw fatal;
            if (cause instanceof PersistenceFailure && cause.getCause() == firstFailure) {
                for (Throwable close : cause.getSuppressed()) firstFailure.addSuppressed(close);
            }
            return new PersistenceFailure(WriteOutcome.NOT_COMMITTED, firstFailure);
        }
        void check() {
            if (!active || Thread.currentThread() != owner)
                throw failed(new IllegalStateException("Session is callback-scoped and thread-confined"));
            if (firstFailure != null) throw new PersistenceFailure(WriteOutcome.NOT_COMMITTED, firstFailure);
        }

        private PreparedStatement prepare(Sql sql, List<Param<?>> params) throws SQLException {
            Objects.requireNonNull(sql); Objects.requireNonNull(params);
            PreparedStatement statement = connection.prepareStatement(sql.text());
            try {
                ParameterMetaData metadata = statement.getParameterMetaData();
                if (metadata.getParameterCount() != params.size()) throw new IllegalArgumentException("SQL parameter count mismatch");
                for (int i = 0; i < params.size(); i++) {
                    Param<?> param = Objects.requireNonNull(params.get(i));
                    if (!compatible(param.codec().postgresType(), metadata.getParameterTypeName(i + 1)))
                        throw new IllegalArgumentException("SQL parameter type mismatch at index " + (i + 1));
                    bind(statement, i + 1, param);
                }
                return statement;
            } catch (Throwable failure) {
                try { statement.close(); } catch (Throwable close) { failure.addSuppressed(close); }
                throw failure;
            }
        }

        private static boolean compatible(String codecType, String sqlType) {
            return switch (codecType) {
                case "integer" -> sqlType.equals("int4");
                case "bigint" -> sqlType.equals("int8");
                case "timestamptz(6)" -> sqlType.equals("timestamptz");
                default -> codecType.startsWith("numeric(") ? sqlType.equals("numeric") : codecType.equals(sqlType);
            };
        }
        private static <V> void bind(PreparedStatement statement, int index, Param<V> param) throws SQLException {
            param.codec().bind(statement, index, param.value());
        }

        @Override public int execute(Sql sql, List<Param<?>> params) {
            check();
            try (PreparedStatement statement = prepare(sql, params)) { return statement.executeUpdate(); }
            catch (Throwable failure) { throw failed(failure); }
        }

        @Override public <R> List<R> query(Sql sql, List<Param<?>> params, RowMapper<R> mapper) {
            check();
            try (PreparedStatement statement = prepare(sql, params); ResultSet rows = statement.executeQuery()) {
                Objects.requireNonNull(mapper);
                List<R> result = new ArrayList<>();
                while (rows.next()) {
                    ScopedRow row = new ScopedRow(rows);
                    try { result.add(Objects.requireNonNull(mapper.map(row), "Row mapper returned null")); }
                    finally { row.valid = false; }
                }
                return List.copyOf(result);
            } catch (Throwable failure) { throw failed(failure); }
        }

        private final class ScopedRow implements Row {
            private final ResultSet rows;
            private boolean valid = true;
            ScopedRow(ResultSet rows) { this.rows = rows; }
            @Override public <V> Optional<V> get(int index, Codec<V> codec) {
                check();
                try {
                    if (!valid) throw new IllegalStateException("Row is valid only during its mapper call");
                    return Objects.requireNonNull(codec).read(rows, index);
                } catch (Throwable failure) { throw failed(failure); }
            }
        }
    }
}
