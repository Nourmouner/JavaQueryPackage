package io.github.nour.nplus1.core.report;

import io.github.nour.nplus1.core.detection.NPlusOneViolation;
import io.github.nour.nplus1.core.model.Severity;

import java.time.Instant;
import java.util.List;

/**
 * Complete report for a single detection context.
 *
 * <p>Contains the list of violations detected, total query count,
 * timing information, and severity summary.
 */
public class NPlusOneReport {

    private final String contextId;
    private final String contextName;
    private final int totalQueryCount;
    private final List<NPlusOneViolation> violations;
    private final Instant timestamp;
    private final long contextDurationMs;

    public NPlusOneReport(String contextId, String contextName,
                           int totalQueryCount, List<NPlusOneViolation> violations,
                           Instant timestamp, long contextDurationMs) {
        this.contextId = contextId;
        this.contextName = contextName;
        this.totalQueryCount = totalQueryCount;
        this.violations = violations != null ? List.copyOf(violations) : List.of();
        this.timestamp = timestamp;
        this.contextDurationMs = contextDurationMs;
    }

    /**
     * Creates an empty report (no context was active).
     */
    public static NPlusOneReport empty() {
        return new NPlusOneReport("none", "none", 0, List.of(), Instant.now(), 0);
    }

    public boolean hasViolations() { return !violations.isEmpty(); }
    public int getViolationCount() { return violations.size(); }
    public String getContextId() { return contextId; }
    public String getContextName() { return contextName; }
    public int getTotalQueryCount() { return totalQueryCount; }
    public List<NPlusOneViolation> getViolations() { return violations; }
    public Instant getTimestamp() { return timestamp; }
    public long getContextDurationMs() { return contextDurationMs; }

    /**
     * Returns the highest severity across all violations.
     */
    public Severity getHighestSeverity() {
        return violations.stream()
                .map(NPlusOneViolation::getSeverity)
                .max(Severity::compareTo)
                .orElse(Severity.LOW);
    }

    /**
     * Returns total wasted time across all N+1 violations in milliseconds.
     */
    public long getTotalWastedTimeMs() {
        return violations.stream()
                .mapToLong(NPlusOneViolation::getTotalTimeMs)
                .sum();
    }

    /**
     * Returns total number of redundant queries across all violations.
     */
    public int getTotalRedundantQueries() {
        return violations.stream()
                .mapToInt(v -> v.getOccurrences() - 1) // -1 because 1 query is needed, rest are redundant
                .sum();
    }

    @Override
    public String toString() {
        return String.format("NPlusOneReport{context='%s', queries=%d, violations=%d, wasted=%dms}",
                contextName, totalQueryCount, violations.size(), getTotalWastedTimeMs());
    }
}
