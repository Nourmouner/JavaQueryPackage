package io.github.nour.nplus1.core.suggestion;

import io.github.nour.nplus1.core.analyzer.QueryFingerprintGenerator;
import io.github.nour.nplus1.core.analyzer.QueryPatternAnalyzer;
import io.github.nour.nplus1.core.model.QueryRecord;
import io.github.nour.nplus1.core.model.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SuggestionEngine")
class SuggestionEngineTest {

    private SuggestionEngine engine;
    private QueryFingerprintGenerator fingerprintGen;

    @BeforeEach
    void setUp() {
        engine = new SuggestionEngine();
        fingerprintGen = new QueryFingerprintGenerator();
    }

    @Test
    @DisplayName("should always suggest JOIN FETCH as primary fix")
    void alwaysSuggestJoinFetch() {
        QueryPatternAnalyzer.DetectedPattern pattern = createPattern(
                "SELECT * FROM orders WHERE user_id = 1", 5);

        List<FixSuggestion> suggestions = engine.suggest(pattern);

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.get(0).getType()).isEqualTo(FixType.JOIN_FETCH);
        assertThat(suggestions.get(0).getPriority()).isEqualTo(1);
    }

    @Test
    @DisplayName("should suggest @EntityGraph as secondary fix")
    void suggestEntityGraph() {
        QueryPatternAnalyzer.DetectedPattern pattern = createPattern(
                "SELECT * FROM orders WHERE user_id = 1", 5);

        List<FixSuggestion> suggestions = engine.suggest(pattern);

        assertThat(suggestions).anyMatch(s -> s.getType() == FixType.ENTITY_GRAPH);
    }

    @Test
    @DisplayName("should suggest @BatchSize for high duplicate counts")
    void suggestBatchSizeForHighCounts() {
        QueryPatternAnalyzer.DetectedPattern pattern = createPattern(
                "SELECT * FROM orders WHERE user_id = 1", 15);

        List<FixSuggestion> suggestions = engine.suggest(pattern);

        assertThat(suggestions).anyMatch(s -> s.getType() == FixType.BATCH_SIZE);
    }

    @Test
    @DisplayName("should suggest SUBSELECT for very high counts")
    void suggestSubselectForVeryHighCounts() {
        QueryPatternAnalyzer.DetectedPattern pattern = createPattern(
                "SELECT * FROM orders WHERE user_id = 1", 25);

        List<FixSuggestion> suggestions = engine.suggest(pattern);

        assertThat(suggestions).anyMatch(s -> s.getType() == FixType.FETCH_MODE_SUBSELECT);
    }

    @Test
    @DisplayName("should always suggest DTO projection")
    void alwaysSuggestDtoProjection() {
        QueryPatternAnalyzer.DetectedPattern pattern = createPattern(
                "SELECT * FROM orders WHERE user_id = 1", 3);

        List<FixSuggestion> suggestions = engine.suggest(pattern);

        assertThat(suggestions).anyMatch(s -> s.getType() == FixType.DTO_PROJECTION);
    }

    @Test
    @DisplayName("suggestions should be sorted by priority")
    void sortedByPriority() {
        QueryPatternAnalyzer.DetectedPattern pattern = createPattern(
                "SELECT * FROM orders WHERE user_id = 1", 25);

        List<FixSuggestion> suggestions = engine.suggest(pattern);

        for (int i = 1; i < suggestions.size(); i++) {
            assertThat(suggestions.get(i).getPriority())
                    .isGreaterThanOrEqualTo(suggestions.get(i - 1).getPriority());
        }
    }

    @Test
    @DisplayName("code examples should not be empty")
    void codeExamplesNotEmpty() {
        QueryPatternAnalyzer.DetectedPattern pattern = createPattern(
                "SELECT * FROM orders WHERE user_id = 1", 5);

        List<FixSuggestion> suggestions = engine.suggest(pattern);

        for (FixSuggestion suggestion : suggestions) {
            assertThat(suggestion.getCodeExample()).isNotBlank();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private QueryPatternAnalyzer.DetectedPattern createPattern(String sql, int occurrences) {
        String fingerprint = fingerprintGen.generate(sql);
        List<QueryRecord> duplicates = new ArrayList<>();
        for (int i = 0; i < occurrences; i++) {
            duplicates.add(QueryRecord.builder()
                    .sql(sql)
                    .fingerprint(fingerprint)
                    .executionTimeNanos(1_000_000)
                    .stackTrace(new StackTraceElement[0])
                    .build());
        }

        QueryRecord initialQuery = QueryRecord.builder()
                .sql("SELECT * FROM users")
                .fingerprint(fingerprintGen.generate("SELECT * FROM users"))
                .executionTimeNanos(2_000_000)
                .stackTrace(new StackTraceElement[0])
                .build();

        return new QueryPatternAnalyzer.DetectedPattern(
                fingerprint, sql, occurrences,
                occurrences * 1_000_000L,
                Severity.fromDuplicateCount(occurrences),
                "com.myapp.UserService.findAll(UserService.java:42)",
                "UserService.findAll",
                initialQuery, duplicates
        );
    }
}
