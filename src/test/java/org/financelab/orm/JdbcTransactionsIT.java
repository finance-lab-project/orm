package org.financelab.orm;

import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class JdbcTransactionsIT {
    static HikariDataSource pool;
    static JdbcTransactions tx;
    static final Instant T1 = Instant.parse("2026-11-01T00:00:00.123456Z");
    static final Instant T2 = T1.plusSeconds(1);
    static final Codec<BigDecimal> MONEY = Codecs.decimal(19, 2);
    static final List<Param<?>> NONE = List.of();

    @BeforeAll static void database() throws Exception {
        String url = Objects.requireNonNull(System.getenv("ORM_TEST_URL"), "Run scripts/verify.sh for a disposable database");
        String user = Objects.requireNonNull(System.getenv("ORM_TEST_USER"));
        Flyway.configure().dataSource(url, user, "").locations("filesystem:target/test-fixture").load().migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url); config.setUsername(user); config.setMaximumPoolSize(3);
        config.setMinimumIdle(0); config.setConnectionTimeout(3000);
        config.addDataSourceProperty("tcpKeepAlive", "true");
        pool = new HikariDataSource(config);
        tx = new JdbcTransactions(pool);
        try (Connection c = pool.getConnection()) {
            assertTrue(c.getMetaData().getDatabaseProductVersion().startsWith("18.6"));
            assertEquals("42.7.14", c.getMetaData().getDriverVersion());
        }
        assertEquals("Pacific/Honolulu", TimeZone.getDefault().getID());
    }
    @AfterAll static void shutdown() { if (pool != null) pool.close(); }
    @AfterEach void noBorrowedConnections() { assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections()); }

    static int execute(WriteSession s, String sql, Param<?>... params) { return s.execute(new Sql(sql), List.of(params)); }
    static <V> V scalar(ReadSession s, String sql, Codec<V> codec, Param<?>... params) {
        return s.query(new Sql(sql), List.of(params), r -> r.get(1, codec).orElseThrow()).getFirst();
    }
    static Param<UUID> id(UUID value) { return Param.of(Codecs.UUID, value); }
    static Param<Instant> at(Instant value) { return Param.of(Codecs.INSTANT, value); }
    static Param<String> text(String value) { return Param.of(Codecs.TEXT, value); }
    static long count(String table, String column, UUID id) {
        return tx.snapshot(s -> scalar(s, "SELECT count(*) FROM budget." + table + " WHERE " + column + " = ?", Codecs.BIGINT, id(id))).value();
    }
    static void catalog(WriteSession s, UUID owner) {
        execute(s, "INSERT INTO budget.catalog_guard VALUES (?, 1, ?)", id(owner), at(T1));
        execute(s, "INSERT INTO budget.catalog_revision VALUES (?, 1, ?)", id(owner), at(T1));
    }
    static UUID seedCatalog() { UUID owner = UUID.randomUUID(); tx.write(s -> { catalog(s, owner); return owner; }); return owner; }

    static UUID seedBudget() {
        UUID owner = seedCatalog(), budget = UUID.randomUUID(), income = UUID.randomUUID(), investment = UUID.randomUUID();
        Param<LocalDate> month = Param.of(Codecs.DATE, LocalDate.of(2026, 11, 1));
        tx.write(s -> {
            execute(s, "INSERT INTO budget.budget VALUES (?)", id(budget));
            execute(s, "INSERT INTO budget.budget_owner VALUES (?, ?)", id(budget), id(owner));
            execute(s, "INSERT INTO budget.budget_month VALUES (?, ?)", id(budget), month);
            execute(s, "INSERT INTO budget.budget_currency VALUES (?, 'RUB')", id(budget));
            execute(s, "INSERT INTO budget.budget_guard (budget_id,owner_id,month,income_line_id,investment_line_id,current_version,last_changed_at) VALUES (?,?,?,?,?,1,?)",
                    id(budget), id(owner), month, id(income), id(investment), at(T1));
            for (UUID line : List.of(income, investment)) {
                Param<String> kind = text(line.equals(income) ? "INCOME" : "INVESTMENT");
                execute(s, "INSERT INTO budget.budget_line VALUES (?)", id(line));
                execute(s, "INSERT INTO budget.line_in_budget VALUES (?, ?)", id(line), id(budget));
                execute(s, "INSERT INTO budget.line_kind_attribute VALUES (?, ?)", id(line), kind);
                execute(s, "INSERT INTO budget.line_key VALUES (?, ?, ?, ?, NULL, ?)", id(line), id(budget), id(owner), kind, at(T1));
                execute(s, "INSERT INTO budget.line_amount_state (line_id, changed_at, state_code) VALUES (?, ?, 'UNSET')", id(line), at(T1));
            }
            execute(s, "INSERT INTO budget.budget_revision VALUES (?, 1, ?, ?, 1)", id(budget), at(T1), id(owner));
            return null;
        });
        return income;
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void deferredAmountPairCommitsInEitherInsertionOrder(boolean amountFirst) {
        UUID line = seedBudget();
        Param<BigDecimal> amount = Param.of(MONEY, new BigDecimal("99999999999999999.99"));
        tx.write(s -> {
            if (amountFirst) execute(s, "INSERT INTO budget.line_amount (line_id,changed_at,amount) VALUES (?,?,?)", id(line), at(T2), amount);
            execute(s, "INSERT INTO budget.line_amount_state (line_id,changed_at,state_code) VALUES (?,?,'SET')", id(line), at(T2));
            if (!amountFirst) execute(s, "INSERT INTO budget.line_amount (line_id,changed_at,amount) VALUES (?,?,?)", id(line), at(T2), amount);
            return null;
        });
        assertEquals(amount.value().orElseThrow(), tx.snapshot(s -> scalar(s, "SELECT amount FROM budget.line_amount WHERE line_id=?", MONEY, id(line))).value());
    }

    @ParameterizedTest @ValueSource(strings={"missing amount", "orphan amount", "mismatched coordinates", "UNSET with amount"})
    void deferredAmountPairFailuresRollBackEveryFact(String mode) {
        UUID line = seedBudget();
        FaultPool tracked = new FaultPool(pool);
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
            if (!mode.equals("orphan amount")) execute(s,
                    "INSERT INTO budget.line_amount_state (line_id,changed_at,state_code) VALUES (?,?,?)",
                    id(line), at(T2), text(mode.equals("UNSET with amount") ? "UNSET" : "SET"));
            if (!mode.equals("missing amount")) execute(s,
                    "INSERT INTO budget.line_amount (line_id,changed_at,amount) VALUES (?,?,?)", id(line),
                    at(mode.equals("mismatched coordinates") ? T2.plusSeconds(1) : T2), Param.of(MONEY, new BigDecimal("0.00")));
            return "must not escape";
        }));
        assertEquals("23503", failure.sqlState().orElseThrow()); assertEquals(WriteOutcome.NOT_COMMITTED, failure.outcome());
        assertEquals(1, tracked.commits); assertEquals(0, count("line_amount", "line_id", line));
        assertEquals(1, count("line_amount_state", "line_id", line));
    }

    @Test void commitPrecedesReturnAndGuardAndFactsUseOneBorrow() {
        UUID owner = UUID.randomUUID(), category = UUID.randomUUID();
        FaultPool tracked = new FaultPool(pool);
        AtomicInteger callbacks = new AtomicInteger();
        Completed<UUID> saved = new JdbcTransactions(tracked).write(s -> {
            callbacks.incrementAndGet();
            int pid = scalar(s, "SELECT pg_backend_pid()", Codecs.INTEGER);
            catalog(s, owner);
            execute(s, "INSERT INTO budget.category VALUES (?)", id(category));
            execute(s, "INSERT INTO budget.category_owner VALUES (?, ?)", id(category), id(owner));
            execute(s, "INSERT INTO budget.category_name VALUES (?, ?, ?)", id(category), at(T1), text("Транспорт"));
            assertEquals(pid, scalar(s, "SELECT pg_backend_pid()", Codecs.INTEGER));
            try (Connection other = pool.getConnection(); PreparedStatement query = other.prepareStatement("SELECT count(*) FROM budget.catalog_guard WHERE owner_id=?")) {
                query.setObject(1, owner);
                try (ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
            }
            return owner;
        });
        assertEquals(owner, saved.value()); assertTrue(saved.cleanupFailures().isEmpty());
        assertEquals(1, callbacks.get()); assertEquals(1, tracked.borrows); assertEquals(1, tracked.commits);
        assertEquals(0, tracked.statements); assertEquals(0, tracked.results);
        assertEquals(1, count("category_name", "category_id", category));
        assertEquals(1, count("catalog_revision", "owner_id", owner));
    }

    @Test void scopeConfigurationAndReadFacade() {
        tx.write(s -> {
            assertEquals("read committed", scalar(s, "SELECT current_setting('transaction_isolation')", Codecs.TEXT));
            assertEquals("off", scalar(s, "SELECT current_setting('transaction_read_only')", Codecs.TEXT));
            assertEquals("UTC", scalar(s, "SELECT current_setting('TimeZone')", Codecs.TEXT));
            return null;
        });
        tx.snapshot(s -> {
            assertFalse(s instanceof WriteSession);
            assertEquals("repeatable read", scalar(s, "SELECT current_setting('transaction_isolation')", Codecs.TEXT));
            assertEquals("on", scalar(s, "SELECT current_setting('transaction_read_only')", Codecs.TEXT));
            assertEquals("UTC", scalar(s, "SELECT current_setting('TimeZone')", Codecs.TEXT));
            return null;
        });
    }

    @Test void callbackFailureRollsBackFactsAndGuardRevision() {
        UUID owner = seedCatalog(), category = UUID.randomUUID();
        IllegalStateException original = new IllegalStateException("injected callback failure");
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> tx.write(s -> {
            execute(s, "INSERT INTO budget.category VALUES (?)", id(category));
            execute(s, "INSERT INTO budget.category_owner VALUES (?, ?)", id(category), id(owner));
            execute(s, "INSERT INTO budget.category_name VALUES (?, ?, ?)", id(category), at(T2), text("Транспорт"));
            execute(s, "UPDATE budget.catalog_guard SET current_version=2, last_changed_at=? WHERE owner_id=?", at(T2), id(owner));
            throw original;
        }));
        assertSame(original, failure.getCause());
        assertEquals(WriteOutcome.NOT_COMMITTED, failure.outcome());
        assertEquals(0, count("category", "category_id", category));
        assertEquals(0, count("category_name", "category_id", category));
        assertEquals(1, tx.snapshot(s -> scalar(s, "SELECT current_version FROM budget.catalog_guard WHERE owner_id=?", Codecs.BIGINT, id(owner))).value());
        assertEquals(1, count("catalog_revision", "owner_id", owner));
    }

    @Test void deferredFailureIsRejectedAtActualCommit() {
        UUID owner = UUID.randomUUID();
        FaultPool tracked = new FaultPool(pool);
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
            execute(s, "INSERT INTO budget.catalog_guard VALUES (?, 1, ?)", id(owner), at(T1));
            return "must not escape";
        }));
        assertEquals(1, tracked.commits);
        assertEquals("23503", failure.sqlState().orElseThrow());
        assertEquals(WriteOutcome.NOT_COMMITTED, failure.outcome());
        assertEquals(0, count("catalog_guard", "owner_id", owner));
        assertEquals(1, tracked.rollbacks);
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void lostCommitResponseIsAlwaysUnknownAndNeverRetried(boolean forwarded) {
        UUID owner = UUID.randomUUID();
        SQLException original = new SQLException("lost commit response", "08006");
        FaultPool tracked = new FaultPool(pool).inject("commit", forwarded, original);
        AtomicInteger callbacks = new AtomicInteger();
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
            callbacks.incrementAndGet(); catalog(s, owner); return owner;
        }));
        assertSame(original, failure.getCause());
        assertEquals(WriteOutcome.UNKNOWN, failure.outcome());
        assertEquals(1, tracked.commits); assertEquals(1, callbacks.get());
        assertEquals(1, tracked.discards); assertEquals(0, tracked.autoCommitResets);
        assertEquals(forwarded ? 1 : 0, count("catalog_guard", "owner_id", owner));
    }

    @ParameterizedTest @ValueSource(strings={"reset", "release"})
    void confirmedCommitRemainsSuccessfulAfterCleanupFailure(String point) {
        UUID owner = UUID.randomUUID();
        SQLException original = new SQLException("cleanup failure", "08006");
        FaultPool tracked = new FaultPool(pool).inject(point, false, original);
        FaultPool used = tracked;
        Completed<UUID> result = new JdbcTransactions(used).write(s -> { catalog(s, owner); return owner; });
        assertEquals(owner, result.value());
        assertFalse(result.cleanupFailures().isEmpty());
        assertEquals(1, used.commits);
        assertEquals(1, count("catalog_guard", "owner_id", owner));
    }

    @Test void repeatableReadKeepsOneSnapshotAcrossConcurrentCommit() throws Exception {
        UUID owner = seedCatalog();
        FaultPool tracked = new FaultPool(pool);
        List<Long> versions = new JdbcTransactions(tracked).snapshot(s -> {
            long first = scalar(s, "SELECT current_version FROM budget.catalog_guard WHERE owner_id=?", Codecs.BIGINT, id(owner));
            int pid = scalar(s, "SELECT pg_backend_pid()", Codecs.INTEGER);
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> tx.write(w -> {
                    execute(w, "UPDATE budget.catalog_guard SET current_version=2, last_changed_at=? WHERE owner_id=?", at(T2), id(owner));
                    execute(w, "INSERT INTO budget.catalog_revision VALUES (?, 2, ?)", id(owner), at(T2));
                    return null;
                })).get(5, TimeUnit.SECONDS);
            }
            assertEquals(pid, scalar(s, "SELECT pg_backend_pid()", Codecs.INTEGER));
            return List.of(first, scalar(s, "SELECT current_version FROM budget.catalog_guard WHERE owner_id=?", Codecs.BIGINT, id(owner)));
        }).value();
        assertEquals(List.of(1L, 1L), versions);
        assertEquals(2, tx.snapshot(s -> scalar(s, "SELECT current_version FROM budget.catalog_guard WHERE owner_id=?", Codecs.BIGINT, id(owner))).value());
        assertEquals(1, tracked.borrows); assertEquals(0, tracked.commits); assertEquals(1, tracked.rollbacks);
    }

    @Test void codecsRoundTripExactlyIncludingNullsAndBoundaryValues() {
        UUID uuid = UUID.randomUUID();
        String label = "  Транспорт ' ; DROP TABLE budget.category; -- 😀  ";
        tx.snapshot(s -> {
            assertEquals(uuid, scalar(s, "SELECT ?::uuid", Codecs.UUID, id(uuid)));
            assertEquals(label, scalar(s, "SELECT ?::text", Codecs.TEXT, text(label)));
            for (int v : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE))
                assertEquals(v, scalar(s, "SELECT ?::integer", Codecs.INTEGER, Param.of(Codecs.INTEGER, v)));
            for (long v : List.of(Long.MIN_VALUE, Long.MAX_VALUE))
                assertEquals(v, scalar(s, "SELECT ?::bigint", Codecs.BIGINT, Param.of(Codecs.BIGINT, v)));
            for (String v : List.of("0.00", "99999999999999999.99", "1.2"))
                assertEquals(new BigDecimal(v).setScale(2), scalar(s, "SELECT ?::numeric(19,2)", MONEY, Param.of(MONEY, new BigDecimal(v))));
            assertEquals(T1, scalar(s, "SELECT ?::timestamptz(6)", Codecs.INSTANT, at(T1)));
            for (LocalDate date : List.of(LocalDate.of(2026, 11, 1), LocalDate.of(-4712, 1, 1), LocalDate.of(5874897, 12, 31)))
                assertEquals(date, scalar(s, "SELECT ?::date", Codecs.DATE, Param.of(Codecs.DATE, date)));
            for (Instant instant : List.of(LocalDate.of(-4712, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC),
                    LocalDate.of(294276, 12, 31).atTime(23, 59, 59, 999999000).toInstant(ZoneOffset.UTC)))
                assertEquals(instant, scalar(s, "SELECT ?::timestamptz(6)", Codecs.INSTANT, at(instant)));
            for (Codec<?> codec : List.of(Codecs.UUID, Codecs.TEXT, Codecs.INTEGER, Codecs.BIGINT, Codecs.DATE, Codecs.INSTANT, MONEY))
                assertNullRoundTrip(s, codec);
            return null;
        });
    }
    private static <V> void assertNullRoundTrip(ReadSession s, Codec<V> codec) {
        assertEquals(List.of(Optional.empty()), s.query(new Sql("SELECT ?::" + codec.postgresType()),
                List.of(new Param<>(codec, Optional.empty())), r -> r.get(1, codec)));
    }

    @ParameterizedTest @ValueSource(strings={"SELECT 'infinity'::date", "SELECT '-infinity'::date", "SELECT 'infinity'::timestamptz", "SELECT '-infinity'::timestamptz", "SELECT 'NaN'::numeric", "SELECT 'Infinity'::numeric"})
    void rejectsNonfiniteDatabaseValues(String sql) {
        assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> {
            if (sql.endsWith("::date")) return scalar(s, sql, Codecs.DATE);
            if (sql.endsWith("::numeric")) return scalar(s, sql, MONEY);
            return scalar(s, sql, Codecs.INSTANT);
        }));
    }

    @Test void swallowedCodecErrorPoisonsScopeAndLeavesNoRows() {
        UUID owner = UUID.randomUUID();
        FaultPool tracked = new FaultPool(pool);
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
            catalog(s, owner);
            assertThrows(PersistenceFailure.class, () -> scalar(s, "SELECT ?::numeric(19,2)", MONEY, Param.of(MONEY, new BigDecimal("1.000"))));
            return "not saved";
        }));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(0, tracked.commits); assertEquals(0, count("catalog_guard", "owner_id", owner));
        assertEquals(0, tracked.statements); assertEquals(0, tracked.results);
    }

    @ParameterizedTest @ValueSource(strings={"borrow", "setReadOnly", "prepareStatement", "statement.bind", "statement.executeUpdate", "statement.close"})
    void acquisitionSetupAndStatementFailuresDoNotCommit(String point) {
        UUID owner = UUID.randomUUID();
        SQLException original = new SQLException("injected operation failure", "57014");
        SQLException next = new SQLException("chained evidence", "XX000"); original.setNextException(next);
        FaultPool tracked = new FaultPool(pool).inject(point, point.equals("statement.close"), original);
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
            catalog(s, owner); return owner;
        }));
        assertSame(original, failure.getCause()); assertSame(next, ((SQLException) failure.getCause()).getNextException());
        assertEquals(WriteOutcome.NOT_COMMITTED, failure.outcome()); assertEquals(0, tracked.commits);
        assertEquals(0, count("catalog_guard", "owner_id", owner));
        assertEquals(0, tracked.statements); assertEquals(0, tracked.results);
    }

    @ParameterizedTest @ValueSource(strings={"statement.executeQuery", "rows.next", "rows.getInt", "rows.close"})
    void queryFailuresCloseRowsAndStatements(String point) {
        SQLException original = new SQLException("injected read failure", "57014");
        FaultPool tracked = new FaultPool(pool).inject(point, point.equals("rows.close"), original);
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).snapshot(s -> scalar(s, "SELECT 1", Codecs.INTEGER)));
        assertSame(original, failure.getCause());
        assertEquals(0, tracked.statements); assertEquals(0, tracked.results);
        assertEquals(0, tracked.commits);
    }

    @Test void callbackAndMapperExceptionsCloseResourcesAndRetainOriginalCause() {
        RuntimeException original = new IllegalStateException("mapper failed");
        FaultPool tracked = new FaultPool(pool);
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).snapshot(s ->
                s.query(new Sql("SELECT 1"), NONE, r -> { throw original; })));
        assertSame(original, failure.getCause());
        assertEquals(0, tracked.statements); assertEquals(0, tracked.results);
    }

    @Test void rollbackFailureIsSuppressedAndConnectionIsDiscardedWithoutReset() {
        RuntimeException original = new IllegalStateException("callback failed");
        SQLException rollback = new SQLException("rollback failed", "08006");
        FaultPool tracked = new FaultPool(pool).inject("rollback", false, rollback);
        UUID owner = UUID.randomUUID();
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
            catalog(s, owner); throw original;
        }));
        assertSame(original, failure.getCause()); assertArrayEquals(new Throwable[]{rollback}, original.getSuppressed());
        assertEquals(1, tracked.discards); assertEquals(0, tracked.autoCommitResets);
        assertEquals(0, count("catalog_guard", "owner_id", owner));
    }

    @Test void failedSnapshotFinishFailsReadAndDiscards() {
        SQLException rollback = new SQLException("snapshot finish failed", "08006");
        FaultPool tracked = new FaultPool(pool).inject("rollback", false, rollback);
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).snapshot(s -> scalar(s, "SELECT 1", Codecs.INTEGER)));
        assertSame(rollback, failure.getCause()); assertEquals(WriteOutcome.NOT_COMMITTED, failure.outcome());
        assertEquals(1, tracked.rollbacks); assertEquals(1, tracked.discards); assertEquals(0, tracked.autoCommitResets);
    }

    @Test void snapshotCleanupFailureFailsRead() {
        SQLException original = new SQLException("read cleanup failed", "08006");
        FaultPool tracked = new FaultPool(pool).inject("reset", false, original);
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).snapshot(s -> scalar(s, "SELECT 1", Codecs.INTEGER)));
        assertSame(original, failure.getCause()); assertEquals(1, tracked.discards);
    }

    @Test void confirmedCommitWithDiscardFailureRetainsBothCleanupDiagnostics() {
        UUID owner = UUID.randomUUID();
        SQLException release = new SQLException("release failed", "08006");
        SQLException discard = new SQLException("discard diagnostic", "08006");
        FaultPool tracked = new FaultPool(pool).inject("release", false, release);
        JdbcTransactions runner = new JdbcTransactions(new JdbcTransactions.Pool() {
            public Connection borrow() throws SQLException { return tracked.borrow(); }
            public void release(Connection c) throws SQLException { tracked.release(c); }
            public void discard(Connection c) throws SQLException { tracked.discard(c); throw discard; }
        });
        Completed<UUID> saved = runner.write(s -> { catalog(s, owner); return owner; });
        assertEquals(owner, saved.value()); assertEquals(List.of(release, discard), saved.cleanupFailures());
        assertEquals(1, tracked.commits); assertEquals(1, count("catalog_guard", "owner_id", owner));
    }

    @Test void unrecognizedCommitExceptionsRemainUnknownEvenWithConstraintSqlstate() {
        FaultPool tracked = new FaultPool(pool).inject("commit", false, new SQLException("not an authoritative server reply", "23503"));
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> 1));
        assertEquals(WriteOutcome.UNKNOWN, failure.outcome()); assertEquals(1, tracked.discards);
    }

    @Test void sessionAndRowAreRevokedAndCrossThreadAndNestedUsePoisonTheScope() {
        AtomicReference<ReadSession> escapedSession = new AtomicReference<>();
        AtomicReference<Row> escapedRow = new AtomicReference<>();
        tx.snapshot(s -> { escapedSession.set(s); return s.query(new Sql("SELECT 1"), NONE, r -> { escapedRow.set(r); return r.get(1, Codecs.INTEGER); }); });
        assertThrows(PersistenceFailure.class, () -> scalar(escapedSession.get(), "SELECT 1", Codecs.INTEGER));
        assertThrows(PersistenceFailure.class, () -> escapedRow.get().get(1, Codecs.INTEGER));
        FaultPool tracked = new FaultPool(pool);
        JdbcTransactions runner = new JdbcTransactions(tracked);
        assertThrows(PersistenceFailure.class, () -> runner.write(s -> {
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                assertInstanceOf(PersistenceFailure.class, executor.submit(() -> {
                    try { return scalar(s, "SELECT 1", Codecs.INTEGER); } catch (PersistenceFailure e) { return e; }
                }).get(5, TimeUnit.SECONDS));
            }
            return null;
        }));
        assertThrows(PersistenceFailure.class, () -> runner.write(s -> {
            assertThrows(PersistenceFailure.class, () -> tx.write(nested -> null)); return null;
        }));
        assertEquals(2, tracked.borrows); assertEquals(0, tracked.commits);
    }

    @Test void rowRevocationAndSwallowedSqlErrorsAreRollbackOnly() {
        assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> {
            AtomicReference<Row> row = new AtomicReference<>();
            s.query(new Sql("SELECT 1"), NONE, r -> { row.set(r); return 1; });
            assertThrows(PersistenceFailure.class, () -> row.get().get(1, Codecs.INTEGER));
            return "not returned";
        }));
        FaultPool tracked = new FaultPool(pool);
        assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
            assertThrows(PersistenceFailure.class, () -> scalar(s, "SELECT 1/0", Codecs.INTEGER)); return "not saved";
        }));
        assertEquals(0, tracked.commits);
    }

    @Test void snapshotCannotMutateThroughReviewedSql() {
        UUID category = UUID.randomUUID();
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> tx.snapshot(s ->
                s.query(new Sql("INSERT INTO budget.category VALUES (?) RETURNING category_id"), List.of(id(category)), r -> r.get(1, Codecs.UUID))));
        assertEquals("25006", failure.sqlState().orElseThrow()); assertEquals(0, count("category", "category_id", category));
    }

    @Test void parameterCountAndTypesAndReadNarrowingFail() {
        assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> scalar(s, "SELECT ?::integer", Codecs.INTEGER)));
        assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> scalar(s, "SELECT ?::integer", Codecs.INTEGER, text("1"))));
        assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> scalar(s, "SELECT 2147483648::bigint", Codecs.INTEGER)));
        assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> scalar(s, "SELECT 1.5::numeric", Codecs.BIGINT)));
    }

    @Test void fatalErrorsAreRethrownAfterRollback() {
        AssertionError fatal = new AssertionError("fatal callback");
        UUID owner = UUID.randomUUID();
        assertSame(fatal, assertThrows(AssertionError.class, () -> tx.write(s -> { catalog(s, owner); throw fatal; })));
        assertEquals(0, count("catalog_guard", "owner_id", owner));
    }
}
