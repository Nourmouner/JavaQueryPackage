package io.github.nour.nplus1.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of a single SQL query execution.
 * Captures everything needed to analyze N+1 patterns:
 * the SQL, its normalized fingerprint, timing, origin stack trace, etc.
 *
 * <p>Built via the fluent {@link Builder} API.
 */
public final class QueryRecord {

    private final String sql;
    private final String fingerprint;
    private final String[] parameters;
    private final long executionTimeNanos;
    private final Instant timestamp;
    private final StackTraceElement[] stackTrace;
    private final String threadName;
    private final QueryType queryType;

    private QueryRecord(Builder builder) {
        this.sql = Objects.requireNonNull(builder.sql, "SQL must not be null");
        this.fingerprint = Objects.requireNonNull(builder.fingerprint, "Fingerprint must not be null");
        this.parameters = builder.parameters != null ? builder.parameters.clone() : new String[0];
        this.executionTimeNanos = builder.executionTimeNanos;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.stackTrace = builder.stackTrace != null ? builder.stackTrace.clone() : new StackTraceElement[0];
        this.threadName = builder.threadName != null ? builder.threadName : Thread.currentThread().getName();
        this.queryType = builder.queryType != null ? builder.queryType : QueryType.fromSql(sql);
    }

    public String getSql() { return sql; }
    public String getFingerprint() { return fingerprint; }
    public String[] getParameters() { return parameters.clone(); }
    public long getExecutionTimeNanos() { return executionTimeNanos; }
    public long getExecutionTimeMs() { return executionTimeNanos / 1_000_000; }
    public Instant getTimestamp() { return timestamp; }
    public StackTraceElement[] getStackTrace() { return stackTrace.clone(); }
    public String getThreadName() { return threadName; }
    public QueryType getQueryType() { return queryType; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sql;
        private String fingerprint;
        private String[] parameters;
        private long executionTimeNanos;
        private Instant timestamp;
        private StackTraceElement[] stackTrace;
        private String threadName;
        private QueryType queryType;

        private Builder() {}

        public Builder sql(String sql) { this.sql = sql; return this; }
        public Builder fingerprint(String fp) { this.fingerprint = fp; return this; }
        public Builder parameters(String[] params) { this.parameters = params; return this; }
        public Builder executionTimeNanos(long nanos) { this.executionTimeNanos = nanos; return this; }
        public Builder timestamp(Instant ts) { this.timestamp = ts; return this; }
        public Builder stackTrace(StackTraceElement[] st) { this.stackTrace = st; return this; }
        public Builder threadName(String name) { this.threadName = name; return this; }
        public Builder queryType(QueryType type) { this.queryType = type; return this; }

        public QueryRecord build() {
            return new QueryRecord(this);
        }
    }

    @Override
    public String toString() {
        return String.format("QueryRecord{type=%s, fingerprint='%s', time=%dms, thread='%s'}",
                queryType, fingerprint, executionTimeNanos / 1_000_000, threadName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryRecord that = (QueryRecord) o;
        return Objects.equals(sql, that.sql)
                && Objects.equals(fingerprint, that.fingerprint)
                && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sql, fingerprint, timestamp);
    }
}
