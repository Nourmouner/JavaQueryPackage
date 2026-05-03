package io.github.nour.nplus1.spring.autoconfigure;

import io.github.nour.nplus1.core.detection.DetectionContext;
import io.github.nour.nplus1.core.detection.DetectionListener;
import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import io.github.nour.nplus1.core.detection.NPlusOneViolation;
import io.github.nour.nplus1.core.interceptor.HibernateStatementInspector;
import io.github.nour.nplus1.core.interceptor.QueryInterceptingDataSource;
import io.github.nour.nplus1.core.report.ConsoleReporter;
import io.github.nour.nplus1.core.report.JsonReporter;
import io.github.nour.nplus1.core.report.Reporter;
import io.github.nour.nplus1.spring.actuator.NPlusOneEndpoint;
import io.github.nour.nplus1.spring.annotation.SuppressNPlusOneAspect;
import io.github.nour.nplus1.spring.metrics.NPlusOneMicrometerExporter;
import io.github.nour.nplus1.spring.web.NPlusOneRequestFilter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@AutoConfiguration(before = { DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class })
@ConditionalOnProperty(prefix = "nplus1.detector", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(NPlusOneProperties.class)
public class NPlusOneAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public NPlusOneDetector nplusOneDetector(NPlusOneProperties p) {
        log.info("🔍 N+1 Query Detector ACTIVE [mode={}, threshold={}, packages={}]",
                p.getMode(), p.getThreshold(), p.getApplicationPackages());

        NPlusOneDetector detector = NPlusOneDetector.builder()
                .threshold(p.getThreshold())
                .applicationPackages(p.getApplicationPackages())
                .maxHistorySize(p.getMaxHistorySize())
                .captureStackTraces(p.isCaptureStackTraces())
                .severityThresholds(p.toSeverityThresholds())
                .build();

        HibernateStatementInspector.registerDetector(detector);

        if (p.getMode() == NPlusOneProperties.Mode.THROW) {
            detector.addListener(new ThrowingListener());
        }
        return detector;
    }

    @Bean
    @ConditionalOnClass(name = "org.hibernate.resource.jdbc.spi.StatementInspector")
    public HibernatePropertiesCustomizer nplusOneHibernateCustomizer() {
        return props -> props.put("hibernate.session_factory.statement_inspector",
                HibernateStatementInspector.class.getName());
    }

    /**
     * Wraps every DataSource bean with a query-timing proxy so wasted-time
     * metrics are accurate. Disable via nplus1.detector.proxy-datasource=false.
     */
    @Bean
    @ConditionalOnProperty(prefix = "nplus1.detector", name = "proxy-datasource",
            havingValue = "true", matchIfMissing = true)
    public static BeanPostProcessor nplusOneDataSourcePostProcessor(
            org.springframework.beans.factory.ObjectProvider<NPlusOneDetector> detectorProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof QueryInterceptingDataSource)) {
                    NPlusOneDetector det = detectorProvider.getIfAvailable();
                    if (det != null) return new QueryInterceptingDataSource(ds, det);
                }
                return bean;
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    static class WebConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public NPlusOneRequestFilter nplusOneRequestFilter(NPlusOneDetector d, NPlusOneProperties p,
                                                           Reporter reporter) {
            return new NPlusOneRequestFilter(d, p, reporter);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public Reporter nplusOneReporter(NPlusOneProperties p) {
        return switch (p.getReportFormat()) {
            case CONSOLE -> new ConsoleReporter(p.isIncludeCodeExamples());
            case JSON -> new JsonReporter();
            case BOTH -> {
                ConsoleReporter c = new ConsoleReporter(p.isIncludeCodeExamples());
                JsonReporter j = new JsonReporter();
                yield report -> {
                    c.report(report);
                    j.report(report);
                };
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnProperty(prefix = "nplus1.detector", name = "actuator-enabled",
            havingValue = "true", matchIfMissing = true)
    static class ActuatorConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public NPlusOneEndpoint nplusOneEndpoint(NPlusOneDetector d) {
            return new NPlusOneEndpoint(d);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "nplus1.detector", name = "metrics-enabled",
            havingValue = "true", matchIfMissing = true)
    static class MetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public NPlusOneMicrometerExporter nplusOneMicrometerExporter(
                MeterRegistry registry, NPlusOneDetector detector) {
            NPlusOneMicrometerExporter exporter = new NPlusOneMicrometerExporter(registry, detector);
            detector.addListener(exporter);
            return exporter;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    static class AopConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public SuppressNPlusOneAspect suppressNPlusOneAspect(NPlusOneDetector detector) {
            return new SuppressNPlusOneAspect(detector);
        }
    }

    @PreDestroy
    public void shutdown() {
        // Detector lifecycle handled per-bean; nothing global to clear here.
    }

    /**
     * Listener that throws on violation. Listeners run inside endContext(),
     * which is called from the filter's finally-block — so we throw AFTER
     * the response is committed only if mode = THROW. To avoid corrupting
     * already-committed responses, we throw here only if no response is
     * in flight (i.e., from a non-web caller like a test).
     */
    static class ThrowingListener implements DetectionListener {
        @Override
        public void onViolationDetected(NPlusOneViolation violation) {
            // The web filter handles THROW mode by checking the report after endContext;
            // for non-web callers (tests, batch jobs), throwing here is appropriate.
            // The filter clears this thread-local before delegating up.
            if (Boolean.TRUE.equals(NON_WEB_CALLER.get())) {
                throw new NPlusOneDetectedException(violation);
            }
        }
    }

    /** Set by non-web callers (tests) to opt into eager throwing. */
    public static final ThreadLocal<Boolean> NON_WEB_CALLER = ThreadLocal.withInitial(() -> false);

    public static class NPlusOneDetectedException extends RuntimeException {
        private final NPlusOneViolation violation;
        public NPlusOneDetectedException(NPlusOneViolation v) {
            super("N+1 query detected: " + v); this.violation = v;
        }
        public NPlusOneViolation getViolation() { return violation; }
    }
}