package io.github.nour.nplus1.spring.metrics;

import io.github.nour.nplus1.core.detection.DetectionContext;
import io.github.nour.nplus1.core.detection.DetectionListener;
import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import io.github.nour.nplus1.core.detection.NPlusOneViolation;
import io.micrometer.core.instrument.*;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics exporter for N+1 detection data.
 *
 * <p>Exports the following metrics:
 * <ul>
 *   <li>{@code nplus1.violations.total} — counter of total violations detected</li>
 *   <li>{@code nplus1.violations.active} — gauge of current violation history size</li>
 *   <li>{@code nplus1.queries.redundant} — counter of redundant queries detected</li>
 *   <li>{@code nplus1.queries.wasted_time} — timer of wasted query execution time</li>
 *   <li>{@code nplus1.contexts.total} — counter of total contexts processed</li>
 *   <li>{@code nplus1.contexts.clean} — counter of contexts with no violations</li>
 * </ul>
 *
 * <p>All metrics are tagged with {@code severity} and {@code origin} for
 * rich dashboarding in Grafana, Datadog, etc.
 */
public class NPlusOneMicrometerExporter implements DetectionListener {

    private static final String PREFIX = "nplus1";

    private final MeterRegistry registry;
    private final NPlusOneDetector detector;

    private final Counter violationCounter;
    private final Counter redundantQueryCounter;
    private final Counter contextCounter;
    private final Counter cleanContextCounter;
    private final AtomicLong activeViolationGauge;

    public NPlusOneMicrometerExporter(MeterRegistry registry, NPlusOneDetector detector) {
        this.registry = registry;
        this.detector = detector;

        // Total violations detected (counter)
        this.violationCounter = Counter.builder(PREFIX + ".violations.total")
                .description("Total N+1 violations detected")
                .register(registry);

        // Redundant queries eliminated indicator (counter)
        this.redundantQueryCounter = Counter.builder(PREFIX + ".queries.redundant")
                .description("Total redundant queries detected across all N+1 violations")
                .register(registry);

        // Total detection contexts processed
        this.contextCounter = Counter.builder(PREFIX + ".contexts.total")
                .description("Total detection contexts processed")
                .register(registry);

        // Clean contexts (no violations)
        this.cleanContextCounter = Counter.builder(PREFIX + ".contexts.clean")
                .description("Detection contexts with no N+1 violations")
                .register(registry);

        // Active violations in history (gauge)
        this.activeViolationGauge = new AtomicLong(0);
        Gauge.builder(PREFIX + ".violations.active", activeViolationGauge, AtomicLong::get)
                .description("Number of violations currently in history")
                .register(registry);

        // Register gauges from detector stats
        Gauge.builder(PREFIX + ".contexts.active", detector, NPlusOneDetector::getActiveContextCount)
                .description("Currently active detection contexts")
                .register(registry);
    }

    @Override
    public void onViolationDetected(NPlusOneViolation violation) {
        // Increment total violation counter
        violationCounter.increment();

        // Count redundant queries (occurrences - 1, since 1 is the needed query)
        redundantQueryCounter.increment(violation.getOccurrences() - 1);

        // Update active gauge
        activeViolationGauge.set(detector.getViolationHistory().size());

        // Record per-severity counters with tags
        Counter.builder(PREFIX + ".violations.by_severity")
                .description("Violations by severity level")
                .tag("severity", violation.getSeverity().name())
                .register(registry)
                .increment();

        // Record wasted time as a distribution summary
        DistributionSummary.builder(PREFIX + ".violations.wasted_time_ms")
                .description("Wasted time per N+1 violation in milliseconds")
                .tag("severity", violation.getSeverity().name())
                .tag("origin", sanitizeTag(violation.getCodeOriginShort()))
                .register(registry)
                .record(violation.getTotalTimeMs());

        // Record occurrence counts
        DistributionSummary.builder(PREFIX + ".violations.occurrences")
                .description("Number of duplicate queries per violation")
                .tag("severity", violation.getSeverity().name())
                .register(registry)
                .record(violation.getOccurrences());
    }

    @Override
    public void onCleanContext(DetectionContext context) {
        contextCounter.increment();
        cleanContextCounter.increment();
    }

    /**
     * Sanitize tag values for metrics systems that have restrictions
     * on tag value characters.
     */
    private String sanitizeTag(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        // Limit length and replace problematic characters
        String sanitized = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.length() > 50 ? sanitized.substring(0, 50) : sanitized;
    }
}
