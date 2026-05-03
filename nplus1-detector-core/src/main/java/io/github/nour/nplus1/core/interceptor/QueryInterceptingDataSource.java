package io.github.nour.nplus1.core.interceptor;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;

public class QueryInterceptingDataSource implements DataSource {

    private static final Logger log = LoggerFactory.getLogger(QueryInterceptingDataSource.class);

    /**
     * When the inspector is also active, we'd double-record each query. The
     * inspector runs first (before execute), so we mark the thread and the
     * inspector skips its record. The proxy then records with real timing.
     */
    static final ThreadLocal<Boolean> INSIDE_PROXY_EXEC = ThreadLocal.withInitial(() -> false);

    private final DataSource delegate;
    private final NPlusOneDetector detector;

    public QueryInterceptingDataSource(DataSource delegate, NPlusOneDetector detector) {
        this.delegate = delegate;
        this.detector = detector;
    }

    public DataSource getDelegate() { return delegate; }

    @Override public Connection getConnection() throws SQLException {
        return proxyConnection(delegate.getConnection());
    }
    @Override public Connection getConnection(String u, String p) throws SQLException {
        return proxyConnection(delegate.getConnection(u, p));
    }

    private Connection proxyConnection(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{Connection.class},
                new ConnectionHandler(real, detector));
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter o) throws SQLException { delegate.setLogWriter(o); }
    @Override public void setLoginTimeout(int s) throws SQLException { delegate.setLoginTimeout(s); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public java.util.logging.Logger getParentLogger() {
        return java.util.logging.Logger.getLogger("N+1-DataSource");
    }
    @Override @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return (T) this;
        return delegate.unwrap(iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    private static Object invoke(Method m, Object target, Object[] args) throws Throwable {
        try { return m.invoke(target, args); }
        catch (InvocationTargetException ite) { throw ite.getCause(); }
    }

    private static boolean isExecuteMethod(String n) {
        return "execute".equals(n) || "executeQuery".equals(n)
                || "executeUpdate".equals(n) || "executeLargeUpdate".equals(n)
                || "executeBatch".equals(n) || "executeLargeBatch".equals(n);
    }

    private static class ConnectionHandler implements InvocationHandler {
        private final Connection real;
        private final NPlusOneDetector detector;
        ConnectionHandler(Connection r, NPlusOneDetector d) { this.real = r; this.detector = d; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = QueryInterceptingDataSource.invoke(method, real, args);
            if ("prepareStatement".equals(method.getName()) && result instanceof PreparedStatement ps)
                return Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class[]{PreparedStatement.class},
                        new PreparedStatementHandler(ps, args.length > 0 ? (String) args[0] : null, detector));
            if ("createStatement".equals(method.getName()) && result instanceof Statement st)
                return Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class[]{Statement.class},
                        new StatementHandler(st, detector));
            return result;
        }
    }

    private static class PreparedStatementHandler implements InvocationHandler {
        private final PreparedStatement real;
        private final String sql;
        private final NPlusOneDetector detector;
        PreparedStatementHandler(PreparedStatement r, String s, NPlusOneDetector d) {
            this.real = r; this.sql = s; this.detector = d;
        }
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isExecuteMethod(method.getName())) {
                INSIDE_PROXY_EXEC.set(true);
                long t0 = System.nanoTime();
                try { return QueryInterceptingDataSource.invoke(method, real, args); }
                finally {
                    long elapsed = System.nanoTime() - t0;
                    String executedSql = sql;
                    if (args != null && args.length > 0 && args[0] instanceof String s) executedSql = s;
                    if (executedSql != null) detector.recordQuery(executedSql, null, elapsed);
                    INSIDE_PROXY_EXEC.set(false);
                }
            }
            return QueryInterceptingDataSource.invoke(method, real, args);
        }
    }

    private static class StatementHandler implements InvocationHandler {
        private final Statement real;
        private final NPlusOneDetector detector;
        StatementHandler(Statement r, NPlusOneDetector d) { this.real = r; this.detector = d; }
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isExecuteMethod(method.getName()) && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                INSIDE_PROXY_EXEC.set(true);
                long t0 = System.nanoTime();
                try { return QueryInterceptingDataSource.invoke(method, real, args); }
                finally {
                    detector.recordQuery(sql, null, System.nanoTime() - t0);
                    INSIDE_PROXY_EXEC.set(false);
                }
            }
            return QueryInterceptingDataSource.invoke(method, real, args);
        }
    }
}