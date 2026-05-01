package io.github.nour.nplus1.core.interceptor;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;

/**
 * Creates a proxied {@link DataSource} that intercepts all SQL queries
 * for N+1 detection with accurate execution timing.
 *
 * <p>Unlike the {@link HibernateStatementInspector} (which captures SQL
 * before execution), this proxy captures the actual execution time of
 * each query, providing more accurate performance data.
 *
 * <p>The proxy is transparent — it wraps the original DataSource and
 * delegates all calls, only adding timing instrumentation to
 * Statement/PreparedStatement execute methods.
 */
public class QueryInterceptingDataSource implements DataSource {

    private static final Logger log = LoggerFactory.getLogger(QueryInterceptingDataSource.class);

    private final DataSource delegate;
    private final NPlusOneDetector detector;

    public QueryInterceptingDataSource(DataSource delegate, NPlusOneDetector detector) {
        this.delegate = delegate;
        this.detector = detector;
    }

    /**
     * Returns the original, unwrapped DataSource.
     */
    public DataSource getDelegate() {
        return delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return proxyConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return proxyConnection(delegate.getConnection(username, password));
    }

    private Connection proxyConnection(Connection realConnection) {
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{Connection.class},
                new ConnectionHandler(realConnection, detector)
        );
    }

    // ─── Delegate methods ────────────────────────────────────────

    @Override
    public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }

    @Override
    public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }

    @Override
    public java.util.logging.Logger getParentLogger() {
        return java.util.logging.Logger.getLogger("N+1-DataSource");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return (T) this;
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    // ─── Connection proxy handler ────────────────────────────────

    private static class ConnectionHandler implements InvocationHandler {
        private final Connection realConnection;
        private final NPlusOneDetector detector;

        ConnectionHandler(Connection realConnection, NPlusOneDetector detector) {
            this.realConnection = realConnection;
            this.detector = detector;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(realConnection, args);

            // Wrap PreparedStatement to intercept executions
            if ("prepareStatement".equals(method.getName()) && result instanceof PreparedStatement ps) {
                String sql = args.length > 0 ? (String) args[0] : null;
                return proxyPreparedStatement(ps, sql);
            }

            // Wrap Statement to intercept executions
            if ("createStatement".equals(method.getName()) && result instanceof Statement stmt) {
                return proxyStatement(stmt);
            }

            return result;
        }

        private PreparedStatement proxyPreparedStatement(PreparedStatement real, String sql) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{PreparedStatement.class},
                    new PreparedStatementHandler(real, sql, detector)
            );
        }

        private Statement proxyStatement(Statement real) {
            return (Statement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{Statement.class},
                    new StatementHandler(real, detector)
            );
        }
    }

    // ─── PreparedStatement proxy handler ─────────────────────────

    private static class PreparedStatementHandler implements InvocationHandler {
        private final PreparedStatement real;
        private final String sql;
        private final NPlusOneDetector detector;

        PreparedStatementHandler(PreparedStatement real, String sql, NPlusOneDetector detector) {
            this.real = real;
            this.sql = sql;
            this.detector = detector;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            // Intercept execute methods for timing
            if (isExecuteMethod(methodName)) {
                long startNanos = System.nanoTime();
                try {
                    return method.invoke(real, args);
                } finally {
                    long elapsed = System.nanoTime() - startNanos;
                    String executedSql = sql;
                    // For executeQuery(String) and execute(String), use the provided SQL
                    if (args != null && args.length > 0 && args[0] instanceof String) {
                        executedSql = (String) args[0];
                    }
                    if (executedSql != null) {
                        detector.recordQuery(executedSql, null, elapsed);
                    }
                }
            }

            return method.invoke(real, args);
        }
    }

    // ─── Statement proxy handler ─────────────────────────────────

    private static class StatementHandler implements InvocationHandler {
        private final Statement real;
        private final NPlusOneDetector detector;

        StatementHandler(Statement real, NPlusOneDetector detector) {
            this.real = real;
            this.detector = detector;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            if (isExecuteMethod(methodName) && args != null && args.length > 0 && args[0] instanceof String sql) {
                long startNanos = System.nanoTime();
                try {
                    return method.invoke(real, args);
                } finally {
                    long elapsed = System.nanoTime() - startNanos;
                    detector.recordQuery(sql, null, elapsed);
                }
            }

            return method.invoke(real, args);
        }
    }

    private static boolean isExecuteMethod(String name) {
        return "execute".equals(name)
                || "executeQuery".equals(name)
                || "executeUpdate".equals(name)
                || "executeLargeUpdate".equals(name);
    }
}
