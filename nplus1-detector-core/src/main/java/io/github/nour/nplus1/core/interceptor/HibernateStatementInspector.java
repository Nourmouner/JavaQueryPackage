package io.github.nour.nplus1.core.interceptor;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hibernate 6 {@link StatementInspector} that intercepts every SQL statement
 * before it is sent to the database and records it for N+1 analysis.
 *
 * <p>This is the primary integration point with Hibernate — it captures SQL
 * with zero overhead when no detection context is active (the detector's
 * {@code recordQuery} silently returns).
 *
 * <h3>Registration:</h3>
 * <p>Automatically registered by the Spring Boot auto-configuration via:
 * <pre>
 * spring.jpa.properties.hibernate.session_factory.statement_inspector=\
 *     io.github.nour.nplus1.core.interceptor.HibernateStatementInspector
 * </pre>
 *
 * <p>Or programmatically via {@link org.hibernate.cfg.Configuration#setStatementInspector}.
 */
public class HibernateStatementInspector implements StatementInspector {

    private static final Logger log = LoggerFactory.getLogger(HibernateStatementInspector.class);

    private static volatile NPlusOneDetector detector;

    /**
     * Sets the detector instance. Called by the auto-configuration.
     * Uses a static field because Hibernate instantiates StatementInspector
     * before Spring's managed bean lifecycle.
     */
    public static void setDetector(NPlusOneDetector detectorInstance) {
        detector = detectorInstance;
        log.debug("HibernateStatementInspector: detector registered");
    }

    /**
     * Clears the detector reference. Used during shutdown.
     */
    public static void clearDetector() {
        detector = null;
    }

    @Override
    public String inspect(String sql) {
        NPlusOneDetector det = detector;
        if (det != null) {
            long startTime = System.nanoTime();

            // Record with estimated execution time (actual timing happens in JDBC)
            // We record here to capture the SQL before any driver transformation
            det.recordQuery(sql, null, 0);
        }

        // Never modify the SQL — we're read-only observers
        return sql;
    }
}
