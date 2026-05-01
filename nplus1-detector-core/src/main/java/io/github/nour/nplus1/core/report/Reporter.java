package io.github.nour.nplus1.core.report;

/**
 * Strategy interface for outputting N+1 reports.
 *
 * <p>Implementations can write to console, files, HTTP endpoints,
 * monitoring systems, etc.
 */
@FunctionalInterface
public interface Reporter {

    /**
     * Process an N+1 detection report.
     * Implementations should be non-blocking when possible.
     *
     * @param report the report to output
     */
    void report(NPlusOneReport report);
}
