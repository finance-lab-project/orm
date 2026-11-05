package org.financelab.orm;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** Injects a single fault outside Hikari's proxies while still using real PostgreSQL. */
final class FaultPool implements JdbcTransactions.Pool {
    final HikariDataSource dataSource;
    final Map<Connection, Connection> borrowed = new IdentityHashMap<>();
    int borrows, commits, rollbacks, releases, discards, statements, results, autoCommitResets;
    int queries, updates;
    Integer reportedUpdates;
    record Binding(String method, int index, Object value) {}
    record StatementTrace(String sql, List<Binding> bindings) {}
    final List<StatementTrace> traces = new ArrayList<>();
    String point;
    boolean after;
    Throwable fault;
    boolean fired;

    FaultPool(HikariDataSource dataSource) { this.dataSource = dataSource; }
    FaultPool inject(String point, boolean after, Throwable fault) {
        this.point = point; this.after = after; this.fault = fault; return this;
    }
    void fire(String point, boolean after) throws Throwable {
        if (!fired && Objects.equals(this.point, point) && this.after == after) { fired = true; throw fault; }
    }
    private void sqlFire(String point, boolean after) throws SQLException {
        try { fire(point, after); }
        catch (SQLException e) { throw e; }
        catch (RuntimeException e) { throw e; }
        catch (Error e) { throw e; }
        catch (Throwable e) { throw new AssertionError(e); }
    }

    @Override public Connection borrow() throws SQLException {
        borrows++;
        sqlFire("borrow", false);
        Connection real = dataSource.getConnection();
        Connection wrapped = proxy(Connection.class, (p, m, args) -> {
            String name = m.getName();
            if (name.equals("commit")) commits++;
            if (name.equals("rollback")) rollbacks++;
            if (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) autoCommitResets++;
            String point = name.equals("setReadOnly") && (commits > 0 || rollbacks > 0) ? "reset" : name;
            fire(point, false);
            Object value = invoke(real, m, args);
            if (name.equals("prepareStatement")) value = statement((PreparedStatement) value, args[0].toString());
            fire(point, true);
            return value;
        });
        borrowed.put(wrapped, real);
        return wrapped;
    }

    private PreparedStatement statement(PreparedStatement real, String sql) {
        boolean application = !sql.startsWith("SET LOCAL");
        StatementTrace trace = new StatementTrace(sql, new ArrayList<>());
        if (application) traces.add(trace);
        statements++;
        return proxy(PreparedStatement.class, (p, m, args) -> {
            String name = m.getName();
            String point = application ? "statement." + (name.startsWith("set") ? "bind" : name) : "setup." + name;
            fire(point, false);
            if (application && name.startsWith("set")) trace.bindings().add(new Binding(name, (int) args[0], args[1]));
            if (application && name.equals("executeQuery")) queries++;
            if (application && name.equals("executeUpdate")) updates++;
            Object value = invoke(real, m, args);
            if (application && name.equals("executeUpdate") && reportedUpdates != null) value = reportedUpdates;
            if (name.equals("close")) statements--;
            if (value instanceof ResultSet rs) value = rows(rs);
            fire(point, true);
            return value;
        });
    }
    private ResultSet rows(ResultSet real) {
        results++;
        return proxy(ResultSet.class, (p, m, args) -> {
            String point = "rows." + m.getName();
            fire(point, false);
            Object value = invoke(real, m, args);
            if (m.getName().equals("close")) results--;
            fire(point, true);
            return value;
        });
    }

    @Override public void release(Connection connection) throws SQLException {
        releases++;
        sqlFire("release", false);
        borrowed.remove(connection).close();
        sqlFire("release", true);
    }
    @Override public void discard(Connection connection) throws SQLException {
        discards++;
        Connection real = borrowed.remove(connection);
        if (real != null) { dataSource.evictConnection(real); real.close(); }
        sqlFire("discard", true);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
