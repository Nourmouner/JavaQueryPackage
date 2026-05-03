package io.github.nour.nplus1.core.interceptor;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hibernate {@link StatementInspector}. Because Hibernate instantiates inspectors
 * itself, we keep a CoW list of registered detectors — each call dispatches to all
 * of them. This supports multiple Spring contexts in one JVM (esp. for tests).
 */
public class HibernateStatementInspector implements StatementInspector {

    private static final Logger log = LoggerFactory.getLogger(HibernateStatementInspector.class);
    private static final CopyOnWriteArrayList<NPlusOneDetector> DETECTORS = new CopyOnWriteArrayList<>();

    public static void registerDetector(NPlusOneDetector detector) {
        if (detector != null && !DETECTORS.contains(detector)) {
            DETECTORS.add(detector);
            log.debug("Registered N+1 detector ({} active)", DETECTORS.size());
        }
    }

    public static void unregisterDetector(NPlusOneDetector detector) {
        DETECTORS.remove(detector);
    }

    /** @deprecated kept for backward compatibility; prefer registerDetector. */
    @Deprecated
    public static void setDetector(NPlusOneDetector detector) { registerDetector(detector); }
    @Deprecated
    public static void clearDetector() { DETECTORS.clear(); }

    @Override
    public String inspect(String sql) {
        if (DETECTORS.isEmpty()) return sql;
        // Avoid double-recording when QueryInterceptingDataSource is also active
        if (Boolean.TRUE.equals(QueryInterceptingDataSource.INSIDE_PROXY_EXEC.get())) return sql;
        for (NPlusOneDetector d : DETECTORS) {
            try { d.recordQuery(sql, null, 0L); }
            catch (Throwable t) { log.debug("recordQuery failed: {}", t.getMessage()); }
        }
        return sql;
    }
}