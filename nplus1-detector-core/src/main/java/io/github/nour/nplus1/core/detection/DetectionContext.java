package io.github.nour.nplus1.core.detection;

import io.github.nour.nplus1.core.model.QueryRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A context that groups queries together for analysis.
 * Typically one context = one HTTP request or one transaction.
 *
 * <p>Thread-safe for concurrent query recording using {@link CopyOnWriteArrayList}.
 */
public class DetectionContext {

    private final String contextId;
    private final String contextName;
    private final Instant startTime;
    private final List<QueryRecord> queries;
    private volatile boolean closed;
    private volatile Instant endTime;

    public DetectionContext(String contextName) {
        this.contextId = UUID.randomUUID().toString().substring(0, 8);
        this.contextName = contextName;
        this.startTime = Instant.now();
        this.queries = new CopyOnWriteArrayList<>();
        this.closed = false;
    }

    /**
     * Records a query execution within this context.
     *
     * @throws IllegalStateException if context is already closed
     */
    public void recordQuery(QueryRecord record) {
        if (closed) {
            throw new IllegalStateException(
                    "Cannot record query — context '" + contextName + "' is already closed");
        }
        queries.add(record);
    }

    /**
     * Closes the context, preventing further query recording.
     *
     * @return an unmodifiable snapshot of all recorded queries
     */
    public List<QueryRecord> close() {
        this.closed = true;
        this.endTime = Instant.now();
        return Collections.unmodifiableList(new ArrayList<>(queries));
    }

    /**
     * Returns the duration this context was active, in milliseconds.
     */
    public long getDurationMs() {
        Instant end = endTime != null ? endTime : Instant.now();
        return end.toEpochMilli() - startTime.toEpochMilli();
    }

    public String getContextId() { return contextId; }
    public String getContextName() { return contextName; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public int getQueryCount() { return queries.size(); }
    public boolean isClosed() { return closed; }

    public List<QueryRecord> getQueries() {
        return Collections.unmodifiableList(new ArrayList<>(queries));
    }

    @Override
    public String toString() {
        return String.format("DetectionContext{id='%s', name='%s', queries=%d, closed=%s}",
                contextId, contextName, queries.size(), closed);
    }
}
