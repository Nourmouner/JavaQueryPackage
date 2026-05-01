package io.github.nour.nplus1.core.analyzer;

import io.github.nour.nplus1.core.model.QueryRecord;
import io.github.nour.nplus1.core.model.QueryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QueryPatternAnalyzer")
class QueryPatternAnalyzerTest {

    private QueryPatternAnalyzer analyzer;
    private QueryFingerprintGenerator fingerprintGen;

    @BeforeEach
    void setUp() {
        StackTraceAnalyzer stackAnalyzer = new StackTraceAnalyzer(List.of("com.myapp"));
        analyzer = new QueryPatternAnalyzer(3, stackAnalyzer);
        fingerprintGen = new QueryFingerprintGenerator();
    }

    @Test
    @DisplayName("should detect N+1 pattern when threshold is exceeded")
    void detectNPlusOnePattern() {
        List<QueryRecord> queries = new ArrayList<>();

        // The "1" query — load all users
        queries.add(createQuery("SELECT * FROM users"));

        // The "N" queries — load orders for each user
        for (int i = 1; i <= 5; i++) {
            queries.add(createQuery("SELECT * FROM orders WHERE user_id = " + i));
        }

        List<QueryPatternAnalyzer.DetectedPattern> patterns = analyzer.analyze(queries);

        assertThat(patterns).hasSize(1);
        assertThat(patterns.get(0).getOccurrences()).isEqualTo(5);
        assertThat(patterns.get(0).getFingerprint()).contains("orders");
    }

    @Test
    @DisplayName("should NOT flag queries below threshold")
    void shouldNotFlagBelowThreshold() {
        List<QueryRecord> queries = new ArrayList<>();
        queries.add(createQuery("SELECT * FROM users"));
        queries.add(createQuery("SELECT * FROM orders WHERE user_id = 1"));
        queries.add(createQuery("SELECT * FROM orders WHERE user_id = 2"));

        // Only 2 duplicate queries — below threshold of 3
        List<QueryPatternAnalyzer.DetectedPattern> patterns = analyzer.analyze(queries);

        assertThat(patterns).isEmpty();
    }

    @Test
    @DisplayName("should detect multiple N+1 patterns")
    void detectMultiplePatterns() {
        List<QueryRecord> queries = new ArrayList<>();

        queries.add(createQuery("SELECT * FROM users"));

        // N+1 on orders
        for (int i = 0; i < 4; i++) {
            queries.add(createQuery("SELECT * FROM orders WHERE user_id = " + i));
        }

        // N+1 on addresses
        for (int i = 0; i < 4; i++) {
            queries.add(createQuery("SELECT * FROM addresses WHERE user_id = " + i));
        }

        List<QueryPatternAnalyzer.DetectedPattern> patterns = analyzer.analyze(queries);

        assertThat(patterns).hasSize(2);
    }

    @Test
    @DisplayName("should handle null and empty input")
    void handleNullAndEmpty() {
        assertThat(analyzer.analyze(null)).isEmpty();
        assertThat(analyzer.analyze(List.of())).isEmpty();
    }

    @Test
    @DisplayName("should reject threshold below 2")
    void rejectInvalidThreshold() {
        StackTraceAnalyzer stackAnalyzer = new StackTraceAnalyzer(List.of());
        assertThatThrownBy(() -> new QueryPatternAnalyzer(1, stackAnalyzer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be >= 2");
    }

    @Test
    @DisplayName("should sort patterns by severity (most critical first)")
    void sortBySeverity() {
        List<QueryRecord> queries = new ArrayList<>();
        queries.add(createQuery("SELECT * FROM parent"));

        // 3 duplicates of pattern A (LOW)
        for (int i = 0; i < 3; i++) {
            queries.add(createQuery("SELECT * FROM child_a WHERE pid = " + i));
        }
        // 15 duplicates of pattern B (HIGH)
        for (int i = 0; i < 15; i++) {
            queries.add(createQuery("SELECT * FROM child_b WHERE pid = " + i));
        }

        List<QueryPatternAnalyzer.DetectedPattern> patterns = analyzer.analyze(queries);

        assertThat(patterns).hasSizeGreaterThanOrEqualTo(2);
        // First pattern should be the more severe one
        assertThat(patterns.get(0).getOccurrences()).isGreaterThan(patterns.get(1).getOccurrences());
    }

    @Test
    @DisplayName("should only analyze SELECT queries")
    void onlyAnalyzeSelects() {
        List<QueryRecord> queries = new ArrayList<>();

        // INSERT queries that look duplicated — should NOT be flagged
        for (int i = 0; i < 10; i++) {
            queries.add(createQuery("INSERT INTO audit_log VALUES (" + i + ", 'event')"));
        }

        List<QueryPatternAnalyzer.DetectedPattern> patterns = analyzer.analyze(queries);

        assertThat(patterns).isEmpty();
    }

    @Test
    @DisplayName("should identify the initial query")
    void identifyInitialQuery() {
        List<QueryRecord> queries = new ArrayList<>();

        // The "1" query
        queries.add(createQuery("SELECT * FROM users"));

        // The "N" queries
        for (int i = 0; i < 5; i++) {
            queries.add(createQuery("SELECT * FROM orders WHERE user_id = " + i));
        }

        List<QueryPatternAnalyzer.DetectedPattern> patterns = analyzer.analyze(queries);

        assertThat(patterns.get(0).getInitialQuery()).isNotNull();
        assertThat(patterns.get(0).getInitialQuery().getSql()).contains("users");
    }

    // ─── Helper Methods ──────────────────────────────────────────

    private QueryRecord createQuery(String sql) {
        return QueryRecord.builder()
                .sql(sql)
                .fingerprint(fingerprintGen.generate(sql))
                .executionTimeNanos(1_000_000) // 1ms
                .stackTrace(new StackTraceElement[0])
                .build();
    }
}
