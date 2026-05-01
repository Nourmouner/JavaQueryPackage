package io.github.nour.nplus1.core.report;

import io.github.nour.nplus1.core.detection.NPlusOneViolation;
import io.github.nour.nplus1.core.suggestion.FixSuggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JSON-structured reporter for integration with log aggregation tools
 * (ELK, Datadog, Splunk, etc.).
 *
 * <p>Outputs reports as structured JSON via SLF4J, making it easy to
 * parse, search, and alert on N+1 violations in production.
 *
 * <p><b>Note:</b> This reporter manually formats JSON to avoid a hard
 * dependency on Jackson. If Jackson is on the classpath, consider using
 * structured logging (e.g., Logstash encoder) instead.
 */
public class JsonReporter implements Reporter {

    private static final Logger log = LoggerFactory.getLogger("N+1-Detector-JSON");

    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT;

    @Override
    public void report(NPlusOneReport report) {
        if (!report.hasViolations()) {
            return; // Only report violations in JSON mode
        }

        String json = toJson(report);
        log.warn(json);
    }

    /**
     * Converts the report to a JSON string.
     * Public for use by actuator endpoints and REST controllers.
     */
    public String toJson(NPlusOneReport report) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append('{');
        appendField(sb, "type", "nplus1_violation_report");
        sb.append(',');
        appendField(sb, "contextId", report.getContextId());
        sb.append(',');
        appendField(sb, "contextName", report.getContextName());
        sb.append(',');
        appendField(sb, "totalQueries", report.getTotalQueryCount());
        sb.append(',');
        appendField(sb, "violationCount", report.getViolationCount());
        sb.append(',');
        appendField(sb, "redundantQueries", report.getTotalRedundantQueries());
        sb.append(',');
        appendField(sb, "wastedTimeMs", report.getTotalWastedTimeMs());
        sb.append(',');
        appendField(sb, "contextDurationMs", report.getContextDurationMs());
        sb.append(',');
        appendField(sb, "highestSeverity", report.getHighestSeverity().name());
        sb.append(',');
        appendField(sb, "timestamp", ISO_FORMAT.format(report.getTimestamp()));
        sb.append(',');

        // Violations array
        sb.append("\"violations\":[");
        List<NPlusOneViolation> violations = report.getViolations();
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(violationToJson(violations.get(i)));
        }
        sb.append(']');

        sb.append('}');
        return sb.toString();
    }

    private String violationToJson(NPlusOneViolation v) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        appendField(sb, "id", v.getId());
        sb.append(',');
        appendField(sb, "severity", v.getSeverity().name());
        sb.append(',');
        appendField(sb, "occurrences", v.getOccurrences());
        sb.append(',');
        appendField(sb, "wastedTimeMs", v.getTotalTimeMs());
        sb.append(',');
        appendField(sb, "codeOrigin", v.getCodeOrigin());
        sb.append(',');
        appendField(sb, "sqlFingerprint", v.getFingerprint());
        sb.append(',');
        appendField(sb, "sqlSample", truncate(v.getSampleSql(), 200));
        sb.append(',');

        // Suggestions array
        sb.append("\"suggestions\":[");
        List<FixSuggestion> suggestions = v.getSuggestions();
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) sb.append(',');
            FixSuggestion fix = suggestions.get(i);
            sb.append('{');
            appendField(sb, "type", fix.getType().name());
            sb.append(',');
            appendField(sb, "explanation", fix.getExplanation());
            sb.append('}');
        }
        sb.append(']');

        sb.append('}');
        return sb.toString();
    }

    private void appendField(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
    }

    private void appendField(StringBuilder sb, String key, long value) {
        sb.append('"').append(key).append("\":").append(value);
    }

    private void appendField(StringBuilder sb, String key, int value) {
        sb.append('"').append(key).append("\":").append(value);
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= maxLen) return oneLine;
        return oneLine.substring(0, maxLen - 3) + "...";
    }
}
