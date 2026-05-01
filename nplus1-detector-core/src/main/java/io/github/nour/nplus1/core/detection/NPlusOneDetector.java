package io.github.nour.nplus1.core.detection;

import io.github.nour.nplus1.core.analyzer.QueryFingerprintGenerator;
import io.github.nour.nplus1.core.analyzer.QueryPatternAnalyzer;
import io.github.nour.nplus1.core.analyzer.QueryPatternAnalyzer.DetectedPattern;
import io.github.nour.nplus1.core.analyzer.StackTraceAnalyzer;
import io.github.nour.nplus1.core.model.QueryRecord;
import io.github.nour.nplus1.core.model.Severity;
import io.github.nour.nplus1.core.report.NPlusOneReport;
import io.github.nour.nplus1.core.suggestion.FixSuggestion;
import io.github.nour.nplus1.core.suggestion.SuggestionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central orchestrator for N+1 query detection.
 *
 * <p>Manages detection contexts (one per thread/request), analyzes query
 * patterns when a context ends, generates fix suggestions, and dispatches
 * events to registered listeners.
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 *   NPlusOneDetector detector = NPlusOneDetector.builder()
 *       .threshold(3)
 *       .applicationPackages("com.myapp")
 *       .build();
 *
 *   detector.startContext("GET /api/users");
 *   // ... queries happen via Hibernate ...
 *   NPlusOneReport report = detector.endContext();
 *   // report contains any detected N+1 violations
 * }</pre>
 *
 * <p>In Spring Boot, this is managed automatically by the auto-configuration.
 */
public class NPlusOneDetector {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneDetector.class);

    private final QueryFingerprintGenerator fingerprintGenerator;
    private final QueryPatternAnalyzer patternAnalyzer;
    private final StackTraceAnalyzer stackTraceAnalyzer;
    private final SuggestionEngine suggestionEngine;
    private final List<DetectionListener> listeners;
    private final boolean captureStackTraces;

    // Thread-local context for the current detection scope
    private final ThreadLocal<DetectionContext> currentContext = new ThreadLocal<>();

    // Historical violations for reporting / actuator
    private final List<NPlusOneViolation> violationHistory;
    private final int maxHistorySize;

    // Active contexts (for monitoring)
    private final Map<String, DetectionContext> activeContexts;

    // Statistics
    private final AtomicLong totalContextsProcessed = new AtomicLong();
    private final AtomicLong totalViolationsDetected = new AtomicLong();
    private final AtomicLong totalQueriesRecorded = new AtomicLong();

    private NPlusOneDetector(Builder builder) {
        this.stackTraceAnalyzer = new StackTraceAnalyzer(builder.applicationPackages);
        this.fingerprintGenerator = new QueryFingerprintGenerator();
        this.patternAnalyzer = new QueryPatternAnalyzer(
                builder.threshold, stackTraceAnalyzer, builder.severityThresholds);
        this.suggestionEngine = new SuggestionEngine();
        this.listeners = new CopyOnWriteArrayList<>(builder.listeners);
        this.violationHistory = Collections.synchronizedList(new LinkedList<>());
        this.maxHistorySize = builder.maxHistorySize;
        this.activeContexts = new ConcurrentHashMap<>();
        this.captureStackTraces = builder.captureStackTraces;
    }

    // ─── Context Lifecycle ───────────────────────────────────────

    /**
     * Starts a new detection context for the current thread.
     *
     * <p>If a context is already active on this thread, it will be
     * auto-closed with a warning log.
     *
     * @param contextName descriptive name (e.g., "GET /api/users")
     * @return the new detection context
     */
    public DetectionContext startContext(String contextName) {
        DetectionContext existing = currentContext.get();
        if (existing != null && !existing.isClosed()) {
            log.warn("Starting new context '{}' while '{}' is still active. Auto-closing previous.",
                    contextName, existing.getContextName());
            endContext();
        }

        DetectionContext context = new DetectionContext(contextName);
        currentContext.set(context);
        activeContexts.put(context.getContextId(), context);
        log.debug("Started detection context: {} [{}]", contextName, context.getContextId());
        return context;
    }

    /**
     * Ends the current detection context, analyzes all recorded queries,
     * and returns a report with any detected N+1 violations.
     *
     * @return the analysis report, or an empty report if no context was active
     */
    public NPlusOneReport endContext() {
        DetectionContext context = currentContext.get();
        if (context == null) {
            log.debug("endContext() called but no context is active on this thread");
            return NPlusOneReport.empty();
        }

        try {
            List<QueryRecord> queries = context.close();
            activeContexts.remove(context.getContextId());
            totalContextsProcessed.incrementAndGet();

            // Analyze patterns
            List<DetectedPattern> patterns = patternAnalyzer.analyze(queries);

            // Build violations with fix suggestions
            List<NPlusOneViolation> violations = new ArrayList<>();
            for (DetectedPattern pattern : patterns) {
                List<FixSuggestion> suggestions = suggestionEngine.suggest(pattern);
                NPlusOneViolation violation = new NPlusOneViolation(
                        UUID.randomUUID().toString().substring(0, 8),
                        pattern,
                        context.getContextName(),
                        suggestions
                );
                violations.add(violation);
                addToHistory(violation);
                totalViolationsDetected.incrementAndGet();

                // Notify listeners
                notifyViolation(violation);
            }

            if (violations.isEmpty()) {
                notifyCleanContext(context);
            }

            return new NPlusOneReport(
                    context.getContextId(),
                    context.getContextName(),
                    queries.size(),
                    violations,
                    context.getStartTime(),
                    context.getDurationMs()
            );
        } catch (Exception e) {
            log.error("Error during N+1 analysis for context '{}': {}",
                    context.getContextName(), e.getMessage(), e);
            notifyError(context, e);
            return NPlusOneReport.empty();
        } finally {
            currentContext.remove();
        }
    }

    // ─── Query Recording ─────────────────────────────────────────

    /**
     * Records a SQL query execution. Called by the interceptor layer.
     *
     * <p>If no context is active on the current thread, the query is silently ignored.
     *
     * @param sql               the raw SQL string
     * @param parameters        bound parameter values (may be null)
     * @param executionTimeNanos execution time in nanoseconds
     */
    public void recordQuery(String sql, String[] parameters, long executionTimeNanos) {
        DetectionContext context = currentContext.get();
        if (context == null || context.isClosed()) {
            return; // No active context — silently ignore
        }

        String fingerprint = fingerprintGenerator.generate(sql);

        StackTraceElement[] stackTrace = captureStackTraces
                ? stackTraceAnalyzer.captureRelevantStackTrace()
                : new StackTraceElement[0];

        QueryRecord record = QueryRecord.builder()
                .sql(sql)
                .fingerprint(fingerprint)
                .parameters(parameters)
                .executionTimeNanos(executionTimeNanos)
                .stackTrace(stackTrace)
                .build();

        context.recordQuery(record);
        totalQueriesRecorded.incrementAndGet();
    }

    /**
     * Checks if a detection context is active on the current thread.
     */
    public boolean hasActiveContext() {
        DetectionContext ctx = currentContext.get();
        return ctx != null && !ctx.isClosed();
    }

    /**
     * Returns the current context name, or null if no context is active.
     */
    public String getCurrentContextName() {
        DetectionContext ctx = currentContext.get();
        return ctx != null ? ctx.getContextName() : null;
    }

    // ─── History & Monitoring ────────────────────────────────────

    public List<NPlusOneViolation> getViolationHistory() {
        synchronized (violationHistory) {
            return List.copyOf(violationHistory);
        }
    }

    public void clearHistory() {
        violationHistory.clear();
    }

    public int getActiveContextCount() {
        return activeContexts.size();
    }

    public long getTotalContextsProcessed() { return totalContextsProcessed.get(); }
    public long getTotalViolationsDetected() { return totalViolationsDetected.get(); }
    public long getTotalQueriesRecorded() { return totalQueriesRecorded.get(); }

    public void addListener(DetectionListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(DetectionListener listener) {
        listeners.remove(listener);
    }

    // ─── Accessors for sub-components ────────────────────────────

    public QueryFingerprintGenerator getFingerprintGenerator() {
        return fingerprintGenerator;
    }

    public StackTraceAnalyzer getStackTraceAnalyzer() {
        return stackTraceAnalyzer;
    }

    // ─── Internals ───────────────────────────────────────────────

    private void addToHistory(NPlusOneViolation violation) {
        synchronized (violationHistory) {
            violationHistory.add(violation);
            // Evict oldest entries if over capacity
            while (violationHistory.size() > maxHistorySize) {
                violationHistory.remove(0);
            }
        }
    }

    private void notifyViolation(NPlusOneViolation violation) {
        for (DetectionListener listener : listeners) {
            try {
                listener.onViolationDetected(violation);
            } catch (Exception e) {
                log.error("Detection listener error on violation: {}", e.getMessage(), e);
            }
        }
    }

    private void notifyCleanContext(DetectionContext context) {
        for (DetectionListener listener : listeners) {
            try {
                listener.onCleanContext(context);
            } catch (Exception e) {
                log.error("Detection listener error on clean context: {}", e.getMessage(), e);
            }
        }
    }

    private void notifyError(DetectionContext context, Throwable error) {
        for (DetectionListener listener : listeners) {
            try {
                listener.onDetectionError(context, error);
            } catch (Exception e) {
                log.error("Detection listener error on error event: {}", e.getMessage(), e);
            }
        }
    }

    // ─── Builder ─────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int threshold = 3;
        private List<String> applicationPackages = new ArrayList<>();
        private List<DetectionListener> listeners = new ArrayList<>();
        private int maxHistorySize = 1000;
        private boolean captureStackTraces = true;
        private Severity.SeverityThresholds severityThresholds = Severity.SeverityThresholds.DEFAULT;

        private Builder() {}

        /** Minimum duplicate queries to flag as N+1. Default: 3. */
        public Builder threshold(int threshold) {
            this.threshold = threshold;
            return this;
        }

        /** Application base packages for stack trace filtering. */
        public Builder applicationPackages(String... packages) {
            this.applicationPackages.addAll(Arrays.asList(packages));
            return this;
        }

        /** Application base packages for stack trace filtering. */
        public Builder applicationPackages(List<String> packages) {
            this.applicationPackages.addAll(packages);
            return this;
        }

        /** Register a detection listener. */
        public Builder addListener(DetectionListener listener) {
            this.listeners.add(listener);
            return this;
        }

        /** Max violations to keep in history. Default: 1000. */
        public Builder maxHistorySize(int size) {
            this.maxHistorySize = size;
            return this;
        }

        /**
         * Whether to capture stack traces on each query.
         * Disabling improves performance but removes code origin info.
         * Default: true.
         */
        public Builder captureStackTraces(boolean capture) {
            this.captureStackTraces = capture;
            return this;
        }

        /** Custom severity thresholds. */
        public Builder severityThresholds(Severity.SeverityThresholds thresholds) {
            this.severityThresholds = thresholds;
            return this;
        }

        public NPlusOneDetector build() {
            return new NPlusOneDetector(this);
        }
    }z
}
