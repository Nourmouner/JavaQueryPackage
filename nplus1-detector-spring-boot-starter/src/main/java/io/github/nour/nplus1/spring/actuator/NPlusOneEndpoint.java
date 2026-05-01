package io.github.nour.nplus1.spring.actuator;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import io.github.nour.nplus1.core.detection.NPlusOneViolation;
import io.github.nour.nplus1.core.suggestion.FixSuggestion;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Spring Boot Actuator endpoint that exposes N+1 detection data.
 *
 * <p>Available at {@code /actuator/nplus1} when actuator is on the classpath.
 *
 * <h3>Endpoints:</h3>
 * <ul>
 *   <li>{@code GET /actuator/nplus1} — summary with all violations</li>
 *   <li>{@code GET /actuator/nplus1/{id}} — details for a specific violation</li>
 *   <li>{@code DELETE /actuator/nplus1} — clear violation history</li>
 * </ul>
 */
@Endpoint(id = "nplus1")
public class NPlusOneEndpoint {

    private final NPlusOneDetector detector;

    public NPlusOneEndpoint(NPlusOneDetector detector) {
        this.detector = detector;
    }

    /**
     * GET /actuator/nplus1
     * Returns a summary of all detected N+1 violations.
     */
    @ReadOperation
    public Map<String, Object> readAll() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Statistics
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalContextsProcessed", detector.getTotalContextsProcessed());
        stats.put("totalViolationsDetected", detector.getTotalViolationsDetected());
        stats.put("totalQueriesRecorded", detector.getTotalQueriesRecorded());
        stats.put("activeContexts", detector.getActiveContextCount());
        result.put("statistics", stats);

        // Violations
        List<NPlusOneViolation> history = detector.getViolationHistory();
        result.put("violationCount", history.size());

        // Group by fingerprint for deduplication
        Map<String, List<NPlusOneViolation>> grouped = history.stream()
                .collect(Collectors.groupingBy(NPlusOneViolation::getFingerprint));

        List<Map<String, Object>> uniqueViolations = new ArrayList<>();
        for (Map.Entry<String, List<NPlusOneViolation>> entry : grouped.entrySet()) {
            List<NPlusOneViolation> violations = entry.getValue();
            NPlusOneViolation latest = violations.get(violations.size() - 1);

            Map<String, Object> violationData = new LinkedHashMap<>();
            violationData.put("id", latest.getId());
            violationData.put("fingerprint", latest.getFingerprint());
            violationData.put("severity", latest.getSeverity().name());
            violationData.put("occurrences", latest.getOccurrences());
            violationData.put("wastedTimeMs", latest.getTotalTimeMs());
            violationData.put("codeOrigin", latest.getCodeOrigin());
            violationData.put("timesSeen", violations.size());
            violationData.put("lastSeenAt", latest.getDetectedAt().toString());
            violationData.put("sqlSample", truncate(latest.getSampleSql(), 200));

            uniqueViolations.add(violationData);
        }

        // Sort by times seen (most frequent first)
        uniqueViolations.sort((a, b) ->
                Integer.compare((int) b.get("timesSeen"), (int) a.get("timesSeen")));

        result.put("violations", uniqueViolations);
        result.put("timestamp", Instant.now().toString());

        return result;
    }

    /**
     * GET /actuator/nplus1/{id}
     * Returns detailed information for a specific violation.
     */
    @ReadOperation
    public Map<String, Object> readOne(@Selector String id) {
        return detector.getViolationHistory().stream()
                .filter(v -> v.getId().equals(id))
                .findFirst()
                .map(this::violationToDetailMap)
                .orElse(Map.of("error", "Violation not found", "id", id));
    }

    /**
     * DELETE /actuator/nplus1
     * Clears the violation history.
     */
    @DeleteOperation
    public Map<String, Object> clearHistory() {
        long count = detector.getViolationHistory().size();
        detector.clearHistory();
        return Map.of(
                "cleared", count,
                "message", "Violation history cleared",
                "timestamp", Instant.now().toString()
        );
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private Map<String, Object> violationToDetailMap(NPlusOneViolation violation) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", violation.getId());
        detail.put("severity", violation.getSeverity().name());
        detail.put("occurrences", violation.getOccurrences());
        detail.put("wastedTimeMs", violation.getTotalTimeMs());
        detail.put("codeOrigin", violation.getCodeOrigin());
        detail.put("contextName", violation.getContextName());
        detail.put("detectedAt", violation.getDetectedAt().toString());
        detail.put("sqlFingerprint", violation.getFingerprint());
        detail.put("sqlSample", violation.getSampleSql());

        // Suggestions
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (FixSuggestion fix : violation.getSuggestions()) {
            Map<String, Object> fixData = new LinkedHashMap<>();
            fixData.put("type", fix.getType().name());
            fixData.put("shortName", fix.getType().getShortName());
            fixData.put("explanation", fix.getExplanation());
            fixData.put("codeExample", fix.getCodeExample());
            fixData.put("priority", fix.getPriority());
            suggestions.add(fixData);
        }
        detail.put("suggestions", suggestions);

        return detail;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= maxLen ? oneLine : oneLine.substring(0, maxLen - 3) + "...";
    }
}
