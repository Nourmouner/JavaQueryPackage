package io.github.nour.nplus1.spring.autoconfigure;

import io.github.nour.nplus1.core.detection.DetectionContext;
import io.github.nour.nplus1.core.detection.DetectionListener;
import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import io.github.nour.nplus1.core.detection.NPlusOneViolation;
import io.github.nour.nplus1.core.interceptor.HibernateStatementInspector;
import io.github.nour.nplus1.core.report.ConsoleReporter;
import io.github.nour.nplus1.core.report.JsonReporter;
import io.github.nour.nplus1.core.report.Reporter;
import io.github.nour.nplus1.spring.actuator.NPlusOneEndpoint;
import io.github.nour.nplus1.spring.annotation.SuppressNPlusOneAspect;
import io.github.nour.nplus1.spring.metrics.NPlusOneMicrometerExporter;
import io.github.nour.nplus1.spring.web.NPlusOneRequestFilter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Spring Boot auto-configuration for the N+1 Query Detector.
 *
 * <p>Automatically activates when {@code nplus1.detector.enabled=true} (default)
 * and Spring Data JPA is on the classpath.
 *
 * <p>Configures:
 * <ul>
 *   <li>{@link NPlusOneDetector} — the core detection engine</li>
 *   <li>{@link HibernateStatementInspector} — Hibernate SQL interceptor</li>
 *   <li>{@link NPlusOneRequestFilter} — per-request detection context (if web app)</li>
 *   <li>{@link NPlusOneEndpoint} — actuator endpoint (if actuator is present)</li>
 *   <li>{@link NPlusOneMicrometerExporter} — metrics exporter (if Micrometer is present)</li>
 *   <li>{@link SuppressNPlusOneAspect} — AOP support for @SuppressNPlusOne</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "nplus1.detector", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(NPlusOneProperties.class)
public class NPlusOneAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneAutoConfiguration.class);

    // ─── Core Beans ──────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public NPlusOneDetector nplusOneDetector(NPlusOneProperties properties) {
        log.info("🔍 N+1 Query Detector is ACTIVE [mode={}, threshold={}, packages={}]",
                properties.getMode(), properties.getThreshold(), properties.getApplicationPackages());

        NPlusOneDetector detector = NPlusOneDetector.builder()
                .threshold(properties.getThreshold())
                .applicationPackages(properties.getApplicationPackages())
                .maxHistorySize(properties.getMaxHistorySize())
                .captureStackTraces(properties.isCaptureStackTraces())
                .severityThresholds(properties.toSeverityThresholds())
                .build();

        // Register reporters as listeners
        Reporter reporter = createReporter(properties);
        registerModeListener(detector, properties, reporter);

        return detector;
    }

    /**
     * Customizes Hibernate properties to register our StatementInspector.
     */
    @Bean
    @ConditionalOnClass(name = "org.hibernate.resource.jdbc.spi.StatementInspector")
    public HibernatePropertiesCustomizer nplusOneHibernateCustomizer(NPlusOneDetector detector) {
        return hibernateProperties -> {
            // Register our StatementInspector with Hibernate
            hibernateProperties.put("hibernate.session_factory.statement_inspector",
                    HibernateStatementInspector.class.getName());

            // Wire the detector into the inspector
            HibernateStatementInspector.setDetector(detector);

            log.debug("Registered HibernateStatementInspector for N+1 detection");
        };
    }

    // ─── Web Integration ─────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public NPlusOneRequestFilter nplusOneRequestFilter(
                NPlusOneDetector detector,
                NPlusOneProperties properties) {
            log.debug("Registering N+1 request filter for web application");
            return new NPlusOneRequestFilter(detector, properties);
        }
    }

    // ─── Actuator Integration ────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnProperty(prefix = "nplus1.detector", name = "actuator-enabled", havingValue = "true", matchIfMissing = true)
    static class ActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public NPlusOneEndpoint nplusOneEndpoint(NPlusOneDetector detector) {
            log.debug("Registering N+1 actuator endpoint at /actuator/nplus1");
            return new NPlusOneEndpoint(detector);
        }
    }

    // ─── Micrometer Metrics ──────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "nplus1.detector", name = "metrics-enabled", havingValue = "true", matchIfMissing = true)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public NPlusOneMicrometerExporter nplusOneMicrometerExporter(
                MeterRegistry registry,
                NPlusOneDetector detector) {
            log.debug("Registering N+1 Micrometer metrics exporter");
            NPlusOneMicrometerExporter exporter = new NPlusOneMicrometerExporter(registry, detector);
            detector.addListener(exporter);
            return exporter;
        }
    }

    // ─── AOP Support ─────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    static class AopConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SuppressNPlusOneAspect suppressNPlusOneAspect() {
            log.debug("Registering @SuppressNPlusOne AOP aspect");
            return new SuppressNPlusOneAspect();
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────

    @PreDestroy
    public void shutdown() {
        HibernateStatementInspector.clearDetector();
        log.debug("N+1 Query Detector shutdown complete");
    }

    // ─── Helper Methods ──────────────────────────────────────────

    private Reporter createReporter(NPlusOneProperties properties) {
        return switch (properties.getReportFormat()) {
            case CONSOLE -> new ConsoleReporter(properties.isIncludeCodeExamples());
            case JSON -> new JsonReporter();
            case BOTH -> report -> {
                new ConsoleReporter(properties.isIncludeCodeExamples()).report(report);
                new JsonReporter().report(report);
            };
        };
    }

    private void registerModeListener(NPlusOneDetector detector, NPlusOneProperties properties, Reporter reporter) {
        detector.addListener(new DetectionListener() {
            @Override
            public void onViolationDetected(NPlusOneViolation violation) {
                switch (properties.getMode()) {
                    case LOG -> {} // Reporter handles logging via endContext
                    case THROW -> throw new NPlusOneDetectedException(violation);
                    case SILENT -> {} // Metrics-only, no logging
                }
            }

            @Override
            public void onCleanContext(DetectionContext context) {
                // No action needed for clean contexts in mode listener
            }
        });

        // Register reporter as a separate listener that always runs
        if (properties.getMode() != NPlusOneProperties.Mode.SILENT) {
            detector.addListener(new ReportingListener(reporter));
        }
    }

    /**
     * Listener that generates and outputs reports for each violation.
     */
    private static class ReportingListener implements DetectionListener {
        private final Reporter reporter;

        ReportingListener(Reporter reporter) {
            this.reporter = reporter;
        }

        @Override
        public void onViolationDetected(NPlusOneViolation violation) {
            // Reports are generated at context end, not per-violation
            // This listener is kept for future per-violation reporting needs
        }

        @Override
        public void onCleanContext(DetectionContext context) {
            // No report needed for clean contexts
        }
    }

    /**
     * Exception thrown when mode=THROW and an N+1 is detected.
     */
    public static class NPlusOneDetectedException extends RuntimeException {
        private final NPlusOneViolation violation;

        public NPlusOneDetectedException(NPlusOneViolation violation) {
            super("N+1 query detected: " + violation.toString());
            this.violation = violation;
        }

        public NPlusOneViolation getViolation() {
            return violation;
        }
    }
}
