package io.github.excalibase.sqlite;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounds how long one statement may run on a connection (EXC-711). sqlite-jdbc's {@code setQueryTimeout} only sets the
 * lock wait and never stops a running query, so each call that runs a statement ({@code execute*}, and {@code next} on its
 * rows) arms a timer that {@link Statement#cancel() cancels} it, which interrupts the connection's running statement, and
 * disarms the timer when the call returns. The bound is per call, never per connection: a pooled connection that is
 * busy all day is not cut off, and a pause between statements counts for nothing.
 *
 * <p>An interrupted statement that is left open keeps the connection interrupted: close it (or its result set) before
 * the connection runs another statement, as JdbcTemplate and try-with-resources do.
 */
final class StatementWatchdog {

    private static final Set<String> RUNS = Set.of("execute", "executeQuery", "executeUpdate", "executeBatch",
            "executeLargeUpdate", "executeLargeBatch");
    private static final Set<String> RESULT_SETS = Set.of("getResultSet", "getGeneratedKeys");
    private static final ScheduledThreadPoolExecutor TIMER = timer();
    private static final Object NOT_OBJECT_METHOD = new Object();

    private StatementWatchdog() {
    }

    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "sqlite-statement-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** {@code connection}, whose statements and result sets stop after {@code limitMillis} of running. */
    static Connection wrap(Connection connection, long limitMillis) {
        return proxy(Connection.class, new Watched(connection, limitMillis, SqliteWriteGate.of(connection), null));
    }

    private static <T> T proxy(Class<T> type, Watched watched) {
        return type.cast(Proxy.newProxyInstance(StatementWatchdog.class.getClassLoader(), new Class<?>[] {type},
                new ProxyIdentity(watched)));
    }

    private static Object invoke(Watched watched, Method method, Object[] args) throws SQLException {
        Object target = watched.target();
        Object own = objectMethod(target, method, args);
        if (own != NOT_OBJECT_METHOD) {
            return own;
        }
        takeTurnToWrite(watched, method, args);
        boolean bounded = target instanceof Statement && RUNS.contains(method.getName())
                || target instanceof ResultSet && "next".equals(method.getName());
        Object result;
        try {
            result = bounded ? runBounded(target, method, args, watched.limitMillis()) : call(target, method, args);
        } finally {
            giveBackTurn(watched, method, args);
        }
        if (result == null) {
            return null;
        }
        Class<?> returned = method.getReturnType();
        if (target instanceof Connection && Statement.class.isAssignableFrom(returned)) {
            String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
            return proxy(statementType(returned), watched.forStatement(returned.cast(result), sql));
        }
        boolean rows = RESULT_SETS.contains(method.getName()) || "executeQuery".equals(method.getName());
        return target instanceof Statement && rows && result instanceof ResultSet set
                ? proxy(ResultSet.class, watched.forStatement(set, null)) : result;
    }

    /** A write waits its turn before the call that runs it, so it waits where a virtual thread gives its carrier back. */
    private static void takeTurnToWrite(Watched watched, Method method, Object[] args) throws SQLException {
        if (!(watched.target() instanceof Statement) || !(RUNS.contains(method.getName()) || "addBatch".equals(method.getName()))) {
            return;
        }
        String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : watched.sql();
        watched.gate().beforeWrite(sql);
    }

    /** The calls that end a transaction, or a write run with auto-commit, hand the file to the next writer. */
    private static void giveBackTurn(Watched watched, Method method, Object[] args) {
        SqliteWriteGate gate = watched.gate();
        String name = method.getName();
        if (watched.target() instanceof Connection) {
            if ("commit".equals(name) || "rollback".equals(name)) {
                gate.end();
            } else if ("close".equals(name)) {
                gate.close();
            } else if ("setAutoCommit".equals(name)) {
                gate.autoCommitSet(Boolean.TRUE.equals(args[0]));
            }
        } else if (watched.target() instanceof Statement && "close".equals(name)) {
            gate.statementClosed();
        }
    }

    /** What a proxy stands for: the real object, the bound its calls run under, its connection's write gate and its SQL. */
    private record Watched(Object target, long limitMillis, SqliteWriteGate gate, String sql) {
        Watched forStatement(Object statementOrRows, String statementSql) {
            return new Watched(statementOrRows, limitMillis, gate, statementSql);
        }
    }

    private record ProxyIdentity(Watched watched) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
            return StatementWatchdog.invoke(watched, method, args);
        }
    }

    /** equals, hashCode and toString are the proxy's own: a proxy is its own identity, never the real object's. */
    private static Object objectMethod(Object target, Method method, Object[] args) {
        if (method.getDeclaringClass() != Object.class) {
            return NOT_OBJECT_METHOD;
        }
        return switch (method.getName()) {
            case "equals" -> args[0] != null && Proxy.isProxyClass(args[0].getClass())
                    && Proxy.getInvocationHandler(args[0]) instanceof ProxyIdentity(Watched other) && other.target() == target;
            case "hashCode" -> System.identityHashCode(target);
            case "toString" -> "watched " + target;
            default -> NOT_OBJECT_METHOD;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> statementType(Class<?> returned) {
        if (CallableStatement.class.isAssignableFrom(returned)) {
            return (Class<T>) CallableStatement.class;
        }
        return (Class<T>) (PreparedStatement.class.isAssignableFrom(returned) ? PreparedStatement.class : Statement.class);
    }

    private static Object runBounded(Object target, Method method, Object[] args, long limitMillis) throws SQLException {
        Statement running = target instanceof Statement statement ? statement : ((ResultSet) target).getStatement();
        ScheduledFuture<?> guard = TIMER.schedule(() -> cancel(running), limitMillis, TimeUnit.MILLISECONDS);
        try {
            return call(target, method, args);
        } finally {
            guard.cancel(false);
        }
    }

    private static void cancel(Statement running) {
        try {
            running.cancel();
        } catch (SQLException _) {
            // the statement ended first, or the connection is closed: nothing is left to stop
        }
    }

    /** Calls through to the real object; what a JDBC method can throw is an SQLException or an unchecked failure. */
    private static Object call(Object target, Method method, Object[] args) throws SQLException {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SQLException sql) {
                throw sql;
            }
            if (cause instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new UndeclaredThrowableException(cause);
        } catch (IllegalAccessException failure) {
            throw new UndeclaredThrowableException(failure);
        }
    }
}
