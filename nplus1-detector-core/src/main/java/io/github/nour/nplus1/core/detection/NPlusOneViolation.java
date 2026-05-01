package io.github.nour.nplus1.core.detection;

import io.github.nour.nplus1.core.analyzer.QueryPatternAnalyzer.DetectedPattern;
import io.github.nour.nplus1.core.model.Severity;
import io.github.nour.nplus1.core.suggestion.FixSuggestion;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Represents a confirmed N+1 violation with all context
 * needed for reporting, metrics, and fixing.
 *
 * <p>Immutable — safe to pass across threads and store in history.
 */
public class NPlusOneViolation {

    private final String id;
    private final DetectedPattern pattern;
    private final String contextName;
    private final Instant detectedAt;
    private final List<FixSuggestion> suggestions;

    public NPlusOneViolation(String id, DetectedPattern pattern,
                              String contextName, List<FixSuggestion> suggestions) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.pattern = Objects.requireNonNull(pattern, "pattern must not be null");
        this.contextName = Objects.requireNonNull(contextName, "contextName must not be null");
        this.detectedAt = Instant.now();
        this.suggestions = suggestions != null ? List.copyOf(suggestions) : List.of();
    }

    public String getId() { return id; }
    public String getFingerprint() { return pattern.getFingerprint(); }
    public String getSampleSql() { return pattern.getSampleSql(); }
    public int getOccurrences() { return pattern.getOccurrences(); }
    public long getTotalTimeMs() { return pattern.getTotalTimeMs(); }
    public Severity getSeverity() { return pattern.getSeverity(); }
    public String getCodeOrigin() { return pattern.getCodeOrigin(); }
    public String getCodeOriginShort() { return pattern.getCodeOriginShort(); }
    public String getContextName() { return contextName; }
    public Instant getDetectedAt() { return detectedAt; }
    public List<FixSuggestion> getSuggestions() { return suggestions; }
    public DetectedPattern getPattern() { return pattern; }

    @Override
    public String toString() {
        return String.format(
                "N+1 Violation [%s] %d queries | %dms wasted | %s | Origin: %s",
                severityLabel(), getOccurrences(), getTotalTimeMs(),
                contextName, getCodeOrigin()
        );
    }

    /**
     * Returns the severity as a visual label with emoji icon.
     */
    public String severityLabel() {
        return switch (getSeverity()) {
            case LOW -> "LOW";
            case MEDIUM -> "MEDIUM";
            case HIGH -> "HIGH";
            case CRITICAL -> "CRITICAL";
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NPlusOneViolation that = (NPlusOneViolation) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
