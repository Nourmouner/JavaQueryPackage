    package io.github.nour.nplus1.core.report;

    import io.github.nour.nplus1.core.detection.NPlusOneViolation;
    import io.github.nour.nplus1.core.suggestion.FixSuggestion;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;

    /**
     * Beautiful, developer-friendly console reporter for N+1 violations.
     *
     * <p>Outputs a visually clear report with violation details, SQL samples,
     * code origins, and fix suggestions — designed to make N+1 issues
     * impossible to miss in your logs.
     */
    public class ConsoleReporter implements Reporter {

        private static final Logger log = LoggerFactory.getLogger("N+1-Detector");

        private static final String BANNER = """
                
                ╔══════════════════════════════════════════════════════════════════╗
                ║                 🔍 N+1 QUERY DETECTOR REPORT                   ║
                ╚══════════════════════════════════════════════════════════════════╝""";

        private static final String SEPARATOR =
                "────────────────────────────────────────────────────────────────────";

        private final boolean includeCodeExamples;

        public ConsoleReporter() {
            this(true);
        }

        public ConsoleReporter(boolean includeCodeExamples) {
            this.includeCodeExamples = includeCodeExamples;
        }

        @Override
        public void report(NPlusOneReport report) {
            if (!report.hasViolations()) {
                log.info("✅ No N+1 queries detected for [{}] ({} queries executed in {}ms)",
                        report.getContextName(),
                        report.getTotalQueryCount(),
                        report.getContextDurationMs());
                return;
            }

            StringBuilder sb = new StringBuilder(2048);

            sb.append(BANNER).append('\n');
            sb.append(String.format("  Context       : %s%n", report.getContextName()));
            sb.append(String.format("  Total Queries : %d%n", report.getTotalQueryCount()));
            sb.append(String.format("  Redundant     : %d (could be eliminated)%n", report.getTotalRedundantQueries()));
            sb.append(String.format("  Violations    : %d detected%n", report.getViolationCount()));
            sb.append(String.format("  Wasted Time   : %dms%n", report.getTotalWastedTimeMs()));
            sb.append(String.format("  Duration      : %dms%n", report.getContextDurationMs()));
            sb.append(SEPARATOR).append('\n');

            int index = 1;
            for (NPlusOneViolation violation : report.getViolations()) {
                sb.append(formatViolation(index++, violation));
            }

            sb.append(SEPARATOR).append('\n');
            sb.append("  💡 Fix these issues to improve your application's performance!\n");
            sb.append("  📖 Learn more: https://github.com/nour/spring-nplus1-detector\n");

            log.warn(sb.toString());
        }

        private String formatViolation(int index, NPlusOneViolation violation) {
            StringBuilder sb = new StringBuilder(512);

            String icon = switch (violation.getSeverity()) {
                case LOW -> "⚪";
                case MEDIUM -> "🟡";
                case HIGH -> "🟠";
                case CRITICAL -> "🔴";
            };

            sb.append(String.format("%n  %s Violation #%d [%s]%n", icon, index, violation.severityLabel()));
            sb.append(String.format("  ├─ Occurrences : %d duplicate queries%n", violation.getOccurrences()));
            sb.append(String.format("  ├─ Time Wasted : %dms%n", violation.getTotalTimeMs()));
            sb.append(String.format("  ├─ Origin      : %s%n", violation.getCodeOrigin()));
            sb.append(String.format("  ├─ SQL Sample  : %s%n", truncate(violation.getSampleSql(), 120)));

            if (!violation.getSuggestions().isEmpty()) {
                sb.append("  ├─ 💡 Suggested Fixes:\n");
                int fixIndex = 1;
                for (FixSuggestion fix : violation.getSuggestions()) {
                    sb.append(String.format("  │   %d. [%s] %s%n",
                            fixIndex++, fix.getType().getShortName(), fix.getExplanation()));

                    if (includeCodeExamples && fixIndex <= 3) { // Show code for top 2 suggestions
                        String indented = fix.getCodeExample().lines()
                                .map(line -> "  │      " + line)
                                .reduce((a, b) -> a + "\n" + b)
                                .orElse("");
                        sb.append(indented).append('\n');
                    }
                }
            }

            sb.append("  │\n");
            return sb.toString();
        }

        private static String truncate(String s, int maxLen) {
            if (s == null) return "null";
            // Collapse newlines for display
            String oneLine = s.replaceAll("\\s+", " ").trim();
            if (oneLine.length() <= maxLen) return oneLine;
            return oneLine.substring(0, maxLen - 3) + "...";
        }
    }
