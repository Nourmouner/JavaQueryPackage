package io.github.nour.nplus1.spring.autoconfigure;

import io.github.nour.nplus1.core.model.Severity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the N+1 query detector.
 *
 * <p>All properties are prefixed with {@code nplus1.detector}.
 *
 * <h3>Example application.yml:</h3>
 * <pre>
 * nplus1:
 *   detector:
 *     enabled: true
 *     threshold: 3
 *     mode: LOG
 *     application-packages:
 *       - com.myapp
 *     capture-stack-traces: true
 *     report-format: CONSOLE
 *     actuator-enabled: true
 *     metrics-enabled: true
 *     severity:
 *       medium: 4
 *       high: 10
 *       critical: 50
 * </pre>
 */
@ConfigurationProperties(prefix = "nplus1.detector")
public class NPlusOneProperties {

    /** Master switch — set to false to completely disable detection. */
    private boolean enabled = true;

    /** Minimum duplicate queries to flag as N+1. Default: 3. */
    private int threshold = 3;

    /**
     * Detection mode:
     * <ul>
     *   <li>LOG — log violations only (default, production-safe)</li>
     *   <li>THROW — throw exception on detection (for tests/dev)</li>
     *   <li>SILENT — detect and record but don't log</li>
     * </ul>
     */
    private Mode mode = Mode.LOG;

    /** Base packages of your application for stack trace filtering. */
    private List<String> applicationPackages = new ArrayList<>();

    /** Whether to capture stack traces (disable for max performance). */
    private boolean captureStackTraces = true;

    /** Report format: CONSOLE (pretty) or JSON (structured). */
    private ReportFormat reportFormat = ReportFormat.CONSOLE;

    /** Whether to expose the /actuator/nplus1 endpoint. */
    private boolean actuatorEnabled = true;

    /** Whether to export Micrometer metrics. */
    private boolean metricsEnabled = true;

    /** Whether to wrap the DataSource to capture accurate query timings. */
    private boolean proxyDatasource = true;

    /** Maximum violations to keep in memory for actuator/metrics. */
    private int maxHistorySize = 1000;

    /** Whether to include code examples in console output. */
    private boolean includeCodeExamples = true;

    /** Custom severity thresholds. */
    private SeverityConfig severity = new SeverityConfig();

    /** Paths to exclude from detection (e.g., "/actuator/**"). */
    private List<String> excludePaths = new ArrayList<>(List.of(
            "/actuator/**",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    ));

    // ─── Getters and Setters ─────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getThreshold() { return threshold; }
    public void setThreshold(int threshold) { this.threshold = threshold; }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public List<String> getApplicationPackages() { return applicationPackages; }
    public void setApplicationPackages(List<String> applicationPackages) {
        this.applicationPackages = applicationPackages;
    }

    public boolean isCaptureStackTraces() { return captureStackTraces; }
    public void setCaptureStackTraces(boolean captureStackTraces) {
        this.captureStackTraces = captureStackTraces;
    }

    public ReportFormat getReportFormat() { return reportFormat; }
    public void setReportFormat(ReportFormat reportFormat) { this.reportFormat = reportFormat; }

    public boolean isActuatorEnabled() { return actuatorEnabled; }
    public void setActuatorEnabled(boolean actuatorEnabled) { this.actuatorEnabled = actuatorEnabled; }

    public boolean isMetricsEnabled() { return metricsEnabled; }
    public void setMetricsEnabled(boolean metricsEnabled) { this.metricsEnabled = metricsEnabled; }

    public boolean isProxyDatasource() { return proxyDatasource; }
    public void setProxyDatasource(boolean proxyDatasource) { this.proxyDatasource = proxyDatasource; }

    public int getMaxHistorySize() { return maxHistorySize; }
    public void setMaxHistorySize(int maxHistorySize) { this.maxHistorySize = maxHistorySize; }

    public boolean isIncludeCodeExamples() { return includeCodeExamples; }
    public void setIncludeCodeExamples(boolean includeCodeExamples) {
        this.includeCodeExamples = includeCodeExamples;
    }

    public SeverityConfig getSeverity() { return severity; }
    public void setSeverity(SeverityConfig severity) { this.severity = severity; }

    public List<String> getExcludePaths() { return excludePaths; }
    public void setExcludePaths(List<String> excludePaths) { this.excludePaths = excludePaths; }

    /**
     * Converts to a core SeverityThresholds instance.
     */
    public Severity.SeverityThresholds toSeverityThresholds() {
        return new Severity.SeverityThresholds(
                severity.getMedium(),
                severity.getHigh(),
                severity.getCritical()
        );
    }

    // ─── Enums ───────────────────────────────────────────────────

    public enum Mode {
        /** Log violations via SLF4J (production-safe). */
        LOG,
        /** Throw an exception when N+1 is detected (for tests/dev). */
        THROW,
        /** Detect and record but don't log or throw (metrics only). */
        SILENT
    }

    public enum ReportFormat {
        /** Pretty console output with emoji and formatting. */
        CONSOLE,
        /** Structured JSON for log aggregation. */
        JSON,
        /** Both console and JSON output. */
        BOTH
    }

    // ─── Nested Configuration ────────────────────────────────────

    public static class SeverityConfig {
        private int medium = 4;
        private int high = 10;
        private int critical = 50;

        public int getMedium() { return medium; }
        public void setMedium(int medium) { this.medium = medium; }

        public int getHigh() { return high; }
        public void setHigh(int high) { this.high = high; }

        public int getCritical() { return critical; }
        public void setCritical(int critical) { this.critical = critical; }
    }
}
