package io.github.nour.nplus1.core.model;

/**
 * Severity levels for N+1 violations, determined by the count
 * of duplicate queries detected.
 *
 * <p>Thresholds are configurable via {@link SeverityThresholds}.
 */
public enum Severity {

    /** Few duplicate queries, minor performance impact. */
    LOW(1),

    /** Noticeable N+1, should be fixed before production. */
    MEDIUM(2),

    /** Severe N+1 pattern, significant performance degradation. */
    HIGH(3),

    /** Extreme N+1, likely to cause outages at scale. */
    CRITICAL(4);

    private final int level;

    Severity(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    /**
     * Determines severity based on duplicate query count using default thresholds.
     */
    public static Severity fromDuplicateCount(int count) {
        return fromDuplicateCount(count, SeverityThresholds.DEFAULT);
    }

    /**
     * Determines severity based on duplicate query count using custom thresholds.
     */
    public static Severity fromDuplicateCount(int count, SeverityThresholds thresholds) {
        if (count >= thresholds.critical()) return CRITICAL;
        if (count >= thresholds.high()) return HIGH;
        if (count >= thresholds.medium()) return MEDIUM;
        return LOW;
    }

    /**
     * Configurable thresholds for severity determination.
     * Allows users to tune what counts as LOW vs CRITICAL for their specific use case.
     *
     * @param medium  minimum duplicate count for MEDIUM severity
     * @param high    minimum duplicate count for HIGH severity
     * @param critical minimum duplicate count for CRITICAL severity
     */
    public record SeverityThresholds(int medium, int high, int critical) {

        /** Default thresholds: MEDIUM=4, HIGH=10, CRITICAL=50 */
        public static final SeverityThresholds DEFAULT = new SeverityThresholds(4, 10, 50);

        public SeverityThresholds {
            if (medium < 2) throw new IllegalArgumentException("medium threshold must be >= 2");
            if (high <= medium) throw new IllegalArgumentException("high must be > medium");
            if (critical <= high) throw new IllegalArgumentException("critical must be > high");
        }
    }
}
