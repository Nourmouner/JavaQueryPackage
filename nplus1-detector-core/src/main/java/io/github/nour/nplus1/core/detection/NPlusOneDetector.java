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
import java.util.function.BooleanSupplier;

public class NPlusOneDetector {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneDetector.class);

    private final QueryFingerprintGenerator fingerprintGenerator;
    private final QueryPatternAnalyzer patternAnalyzer;
    private final StackTraceAnalyzer stackTraceAnalyzer;
    private final SuggestionEngine suggestionEngine;
    private final List<DetectionListener> listeners;
    private final boolean captureStackTraces;

    private final ThreadLocal<DetectionContext> currentContext = new ThreadLocal<>();

    /** ArrayDeque-based history with O(1) eviction. */
    private final ArrayDeque<NPlusOneViolation> violationHistory;
    private final int maxHistorySize;

    private final Map<String, DetectionContext> activeContexts;

    private final AtomicLong totalContextsProcessed = new AtomicLong();
    private final AtomicLong totalViolationsDetected = new AtomicLong();
    private final AtomicLong totalQueriesRecorded = new AtomicLong();

    /** Pluggable predicate that, if true, causes recordQuery to be a no-op. */
    private volatile BooleanSupplier suppressionCheck = () -> false;

    private NPlusOneDetector(Builder builder) {
        this.stackTraceAnalyzer = new StackTraceAnalyzer(builder.applicationPackages);
        this.fingerprintGenerator = new QueryFingerprintGenerator();
        this.patternAnalyzer = new QueryPatternAnalyzer(
                builder.threshold, stackTraceAnalyzer, builder.severityThresholds);
        this.suggestionEngine = new SuggestionEngine();
        this.listeners = new CopyOnWriteArrayList<>(builder.listeners);
        this.violationHistory = new ArrayDeque<>(builder.maxHistorySize);
        this.maxHistorySize = builder.maxHistorySize;
        this.activeContexts = new ConcurrentHashMap<>();
        this.captureStackTraces = builder.captureStackTraces;
    }

    /** Allows the AOP aspect (or anything else) to register a per-thread suppression flag. */
    public void setSuppressionCheck(BooleanSupplier check) {
        this.suppressionCheck = (check != null) ? check : (() -> false);
    }

    public boolean isSuppressed() {
        try { return suppressionCheck.getAsBoolean(); }
        catch (Exception e) { return false; }
    }

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

    public NPlusOneReport endContext() {
        DetectionContext context = currentContext.get();
        if (context == null) return NPlusOneReport.empty();

        try {
            List<QueryRecord> queries = context.close();
            activeContexts.remove(context.getContextId());
            totalContextsProcessed.incrementAndGet();

            // If suppressed, return a clean report
            if (isSuppressed()) {
                notifyCleanContext(context);
                return new NPlusOneReport(
                        context.getContextId(), context.getContextName(),
                        queries.size(), List.of(),
                        context.getStartTime(), context.getDurationMs());
            }

            List<DetectedPattern> patterns = patternAnalyzer.analyze(queries);
            List<NPlusOneViolation> violations = new ArrayList<>();
            for (DetectedPattern pattern : patterns) {
                List<FixSuggestion> suggestions = suggestionEngine.suggest(pattern);
                NPlusOneViolation violation = new NPlusOneViolation(
                        UUID.randomUUID().toString().substring(0, 8),
                        pattern, context.getContextName(), suggestions);
                violations.add(violation);
                addToHistory(violation);
                totalViolationsDetected.incrementAndGet();
                notifyViolation(violation);
            }
            if (violations.isEmpty()) notifyCleanContext(context);

            return new NPlusOneReport(
                    context.getContextId(), context.getContextName(),
                    queries.size(), violations,
                    context.getStartTime(), context.getDurationMs());
        } catch (Exception e) {
            log.error("Error during N+1 analysis for context '{}': {}",
                    context.getContextName(), e.getMessage(), e);
            notifyError(context, e);
            return NPlusOneReport.empty();
        } finally {
            currentContext.remove();
        }
    }

    public void recordQuery(String sql, String[] parameters, long executionTimeNanos) {
        DetectionContext context = currentContext.get();
        if (context == null || context.isClosed()) return;
        if (isSuppressed()) return;                 // <-- HONOR @SuppressNPlusOne

        String fingerprint = fingerprintGenerator.generate(sql);
        StackTraceElement[] stackTrace = captureStackTraces
                ? stackTraceAnalyzer.captureRelevantStackTrace()
                : new StackTraceElement[0];

        QueryRecord record = QueryRecord.builder()
                .sql(sql).fingerprint(fingerprint).parameters(parameters)
                .executionTimeNanos(executionTimeNanos).stackTrace(stackTrace).build();

        context.recordQuery(record);
        totalQueriesRecorded.incrementAndGet();
    }

    public boolean hasActiveContext() {
        DetectionContext ctx = currentContext.get();
        return ctx != null && !ctx.isClosed();
    }
    public String getCurrentContextName() {
        DetectionContext ctx = currentContext.get();
        return ctx != null ? ctx.getContextName() : null;
    }

    public List<NPlusOneViolation> getViolationHistory() {
        synchronized (violationHistory) { return List.copyOf(violationHistory); }
    }
    public void clearHistory() {
        synchronized (violationHistory) { violationHistory.clear(); }
    }
    public int getActiveContextCount() { return activeContexts.size(); }
    public long getTotalContextsProcessed() { return totalContextsProcessed.get(); }
    public long getTotalViolationsDetected() { return totalViolationsDetected.get(); }
    public long getTotalQueriesRecorded() { return totalQueriesRecorded.get(); }

    public void addListener(DetectionListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }
    public void removeListener(DetectionListener listener) { listeners.remove(listener); }

    public QueryFingerprintGenerator getFingerprintGenerator() { return fingerprintGenerator; }
    public StackTraceAnalyzer getStackTraceAnalyzer() { return stackTraceAnalyzer; }

    private void addToHistory(NPlusOneViolation violation) {
        synchronized (violationHistory) {
            violationHistory.addLast(violation);
            while (violationHistory.size() > maxHistorySize) violationHistory.removeFirst(); // O(1)
        }
    }
    private void notifyViolation(NPlusOneViolation v) {
        for (DetectionListener l : listeners) {
            try { l.onViolationDetected(v); }
            catch (Exception e) { log.error("listener error: {}", e.getMessage(), e); }
        }
    }
    private void notifyCleanContext(DetectionContext c) {
        for (DetectionListener l : listeners) {
            try { l.onCleanContext(c); }
            catch (Exception e) { log.error("listener error: {}", e.getMessage(), e); }
        }
    }
    private void notifyError(DetectionContext c, Throwable t) {
        for (DetectionListener l : listeners) {
            try { l.onDetectionError(c, t); }
            catch (Exception e) { log.error("listener error: {}", e.getMessage(), e); }
        }
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int threshold = 3;
        private final List<String> applicationPackages = new ArrayList<>();
        private final List<DetectionListener> listeners = new ArrayList<>();
        private int maxHistorySize = 1000;
        private boolean captureStackTraces = true;
        private Severity.SeverityThresholds severityThresholds = Severity.SeverityThresholds.DEFAULT;
        private Builder() {}
        public Builder threshold(int t) { this.threshold = t; return this; }
        public Builder applicationPackages(String... p) { this.applicationPackages.addAll(Arrays.asList(p)); return this; }
        public Builder applicationPackages(List<String> p) { this.applicationPackages.addAll(p); return this; }
        public Builder addListener(DetectionListener l) { this.listeners.add(l); return this; }
        public Builder maxHistorySize(int s) { this.maxHistorySize = s; return this; }
        public Builder captureStackTraces(boolean c) { this.captureStackTraces = c; return this; }
        public Builder severityThresholds(Severity.SeverityThresholds t) { this.severityThresholds = t; return this; }
        public NPlusOneDetector build() { return new NPlusOneDetector(this); }
    }
}