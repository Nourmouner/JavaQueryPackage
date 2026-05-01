package io.github.nour.nplus1.core.analyzer;

import io.github.nour.nplus1.core.model.QueryRecord;
import io.github.nour.nplus1.core.model.QueryType;
import io.github.nour.nplus1.core.model.Severity;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The brain of N+1 detection.
 *
 * <p>Groups query records by fingerprint and identifies N+1 patterns.
 * A pattern is flagged when the same normalized SQL appears more times
 * than the configured threshold within a single detection context.
 */
public class QueryPatternAnalyzer {

    private final int threshold;
    private final StackTraceAnalyzer stackTraceAnalyzer;
    private final Severity.SeverityThresholds severityThresholds;

    /**
     * @param threshold            minimum number of duplicate queries to flag as N+1
     * @param stackTraceAnalyzer   for identifying code origins
     */
    public QueryPatternAnalyzer(int threshold, StackTraceAnalyzer stackTraceAnalyzer) {
        this(threshold, stackTraceAnalyzer, Severity.SeverityThresholds.DEFAULT);
    }

    /**
     * @param threshold            minimum number of duplicate queries to flag as N+1
     * @param stackTraceAnalyzer   for identifying code origins
     * @param severityThresholds   custom severity thresholds
     */
    public QueryPatternAnalyzer(int threshold, StackTraceAnalyzer stackTraceAnalyzer,
                                 Severity.SeverityThresholds severityThresholds) {
        if (threshold < 2) {
            throw new IllegalArgumentException("Threshold must be >= 2 (N+1 means at least 2 queries)");
        }
        this.threshold = threshold;
        this.stackTraceAnalyzer = stackTraceAnalyzer;
        this.severityThresholds = severityThresholds;
    }

    /**
     * Analyzes a list of query records from a single context (e.g., one HTTP request)
     * and returns all detected N+1 violations.
     *
     * @param queries list of queries recorded in the context
     * @return detected patterns, sorted by severity (most critical first)
     */
    public List<DetectedPattern> analyze(List<QueryRecord> queries) {
        if (queries == null || queries.size() < threshold) {
            return Collections.emptyList();
        }

        // Group SELECT queries by fingerprint (N+1 only applies to SELECT)
        Map<String, List<QueryRecord>> grouped = queries.stream()
                .filter(q -> q.getQueryType() == QueryType.SELECT)
                .collect(Collectors.groupingBy(
                        QueryRecord::getFingerprint,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<DetectedPattern> violations = new ArrayList<>();

        for (Map.Entry<String, List<QueryRecord>> entry : grouped.entrySet()) {
            List<QueryRecord> duplicates = entry.getValue();

            if (duplicates.size() >= threshold) {
                DetectedPattern pattern = buildDetectedPattern(
                        entry.getKey(), duplicates, queries);
                violations.add(pattern);
            }
        }

        // Sort by severity descending, then by occurrence count descending
        violations.sort(Comparator
                .comparing(DetectedPattern::getSeverity).reversed()
                .thenComparing(Comparator.comparingInt(DetectedPattern::getOccurrences).reversed()));

        return violations;
    }

    private DetectedPattern buildDetectedPattern(
            String fingerprint,
            List<QueryRecord> duplicates,
            List<QueryRecord> allQueries) {

        // Find the "1" query — the initial query that loaded the parent entity
        QueryRecord initialQuery = findInitialQuery(fingerprint, allQueries);

        // Compute total wasted time
        long totalTimeNanos = duplicates.stream()
                .mapToLong(QueryRecord::getExecutionTimeNanos)
                .sum();

        // Find the code origin from the first duplicate's stack trace
        String origin = duplicates.stream()
                .findFirst()
                .map(q -> stackTraceAnalyzer.formatOrigin(q.getStackTrace()))
                .orElse("unknown");

        // Also get short origin for metrics/tags
        String originShort = duplicates.stream()
                .findFirst()
                .map(q -> stackTraceAnalyzer.formatOriginShort(q.getStackTrace()))
                .orElse("unknown");

        return new DetectedPattern(
                fingerprint,
                duplicates.get(0).getSql(),
                duplicates.size(),
                totalTimeNanos,
                Severity.fromDuplicateCount(duplicates.size(), severityThresholds),
                origin,
                originShort,
                initialQuery,
                duplicates
        );
    }

    /**
     * Heuristic: the "1" in N+1 is likely the first SELECT query with a
     * DIFFERENT fingerprint that appeared before the repeated queries began.
     */
    private QueryRecord findInitialQuery(String repeatedFingerprint, List<QueryRecord> allQueries) {
        QueryRecord firstRepeated = null;

        // Find the timestamp of the first repeated query
        for (QueryRecord q : allQueries) {
            if (q.getFingerprint().equals(repeatedFingerprint)) {
                firstRepeated = q;
                break;
            }
        }

        if (firstRepeated == null) return null;

        // Find the last SELECT query before the first repeated query
        QueryRecord candidate = null;
        for (QueryRecord q : allQueries) {
            if (q == firstRepeated) break;
            if (q.getQueryType() == QueryType.SELECT
                    && !q.getFingerprint().equals(repeatedFingerprint)) {
                candidate = q;
            }
        }

        return candidate;
    }

    /**
     * Represents a detected N+1 pattern with full context.
     */
    public static class DetectedPattern {

        private final String fingerprint;
        private final String sampleSql;
        private final int occurrences;
        private final long totalTimeNanos;
        private final Severity severity;
        private final String codeOrigin;
        private final String codeOriginShort;
        private final QueryRecord initialQuery;
        private final List<QueryRecord> duplicateQueries;

        public DetectedPattern(String fingerprint, String sampleSql, int occurrences,
                               long totalTimeNanos, Severity severity, String codeOrigin,
                               String codeOriginShort,
                               QueryRecord initialQuery, List<QueryRecord> duplicateQueries) {
            this.fingerprint = fingerprint;
            this.sampleSql = sampleSql;
            this.occurrences = occurrences;
            this.totalTimeNanos = totalTimeNanos;
            this.severity = severity;
            this.codeOrigin = codeOrigin;
            this.codeOriginShort = codeOriginShort;
            this.initialQuery = initialQuery;
            this.duplicateQueries = List.copyOf(duplicateQueries);
        }

        public String getFingerprint() { return fingerprint; }
        public String getSampleSql() { return sampleSql; }
        public int getOccurrences() { return occurrences; }
        public long getTotalTimeNanos() { return totalTimeNanos; }
        public long getTotalTimeMs() { return totalTimeNanos / 1_000_000; }
        public Severity getSeverity() { return severity; }
        public String getCodeOrigin() { return codeOrigin; }
        public String getCodeOriginShort() { return codeOriginShort; }
        public QueryRecord getInitialQuery() { return initialQuery; }
        public List<QueryRecord> getDuplicateQueries() { return duplicateQueries; }
    }
}
