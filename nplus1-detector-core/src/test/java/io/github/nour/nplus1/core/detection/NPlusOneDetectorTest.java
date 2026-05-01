package io.github.nour.nplus1.core.detection;

import io.github.nour.nplus1.core.report.NPlusOneReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NPlusOneDetector — Integration")
class NPlusOneDetectorTest {

    private NPlusOneDetector detector;

    @BeforeEach
    void setUp() {
        detector = NPlusOneDetector.builder()
                .threshold(3)
                .applicationPackages("com.myapp")
                .maxHistorySize(100)
                .build();
    }

    @Test
    @DisplayName("should detect N+1 queries within a context")
    void detectNPlusOneInContext() {
        detector.startContext("TEST /api/users");

        // Simulate: 1 query to load users + 5 queries to load orders
        detector.recordQuery("SELECT * FROM users", null, 5_000_000);
        for (int i = 1; i <= 5; i++) {
            detector.recordQuery(
                    "SELECT * FROM orders WHERE user_id = " + i,
                    new String[]{String.valueOf(i)},
                    1_000_000
            );
        }

        NPlusOneReport report = detector.endContext();

        assertThat(report.hasViolations()).isTrue();
        assertThat(report.getViolationCount()).isEqualTo(1);
        assertThat(report.getViolations().get(0).getOccurrences()).isEqualTo(5);
        assertThat(report.getTotalQueryCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("should return empty report when no violations")
    void noViolations() {
        detector.startContext("TEST /api/health");

        detector.recordQuery("SELECT 1", null, 100_000);
        detector.recordQuery("SELECT * FROM users WHERE id = 1", null, 500_000);

        NPlusOneReport report = detector.endContext();

        assertThat(report.hasViolations()).isFalse();
        assertThat(report.getTotalQueryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("should increment statistics correctly")
    void trackStatistics() {
        assertThat(detector.getTotalContextsProcessed()).isZero();

        detector.startContext("ctx-1");
        detector.recordQuery("SELECT 1", null, 0);
        detector.endContext();

        detector.startContext("ctx-2");
        detector.recordQuery("SELECT 2", null, 0);
        detector.endContext();

        assertThat(detector.getTotalContextsProcessed()).isEqualTo(2);
        assertThat(detector.getTotalQueriesRecorded()).isEqualTo(2);
    }

    @Test
    @DisplayName("should notify listeners on violation")
    void notifyListeners() {
        AtomicInteger violationCount = new AtomicInteger();
        detector.addListener(new DetectionListener() {
            @Override
            public void onViolationDetected(NPlusOneViolation violation) {
                violationCount.incrementAndGet();
            }
        });

        detector.startContext("TEST");
        for (int i = 0; i < 5; i++) {
            detector.recordQuery("SELECT * FROM items WHERE parent_id = " + i, null, 0);
        }
        detector.endContext();

        assertThat(violationCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("should maintain violation history")
    void maintainHistory() {
        detector.startContext("TEST");
        for (int i = 0; i < 5; i++) {
            detector.recordQuery("SELECT * FROM items WHERE id = " + i, null, 0);
        }
        detector.endContext();

        assertThat(detector.getViolationHistory()).isNotEmpty();

        detector.clearHistory();
        assertThat(detector.getViolationHistory()).isEmpty();
    }

    @Test
    @DisplayName("should generate fix suggestions for detected violations")
    void generateFixSuggestions() {
        detector.startContext("TEST");

        detector.recordQuery("SELECT * FROM users", null, 0);
        for (int i = 0; i < 5; i++) {
            detector.recordQuery("SELECT * FROM orders WHERE user_id = " + i, null, 0);
        }

        NPlusOneReport report = detector.endContext();

        assertThat(report.getViolations().get(0).getSuggestions()).isNotEmpty();
        assertThat(report.getViolations().get(0).getSuggestions().get(0).getCodeExample())
                .isNotBlank();
    }

    @Test
    @DisplayName("should silently ignore queries when no context is active")
    void ignoreQueriesWithoutContext() {
        // Should not throw
        detector.recordQuery("SELECT * FROM users", null, 0);

        assertThat(detector.getTotalQueriesRecorded()).isZero();
    }

    @Test
    @DisplayName("should return empty report when endContext called without startContext")
    void endContextWithoutStart() {
        NPlusOneReport report = detector.endContext();

        assertThat(report).isNotNull();
        assertThat(report.hasViolations()).isFalse();
        assertThat(report.getTotalQueryCount()).isZero();
    }

    @Test
    @DisplayName("should auto-close previous context when starting new one")
    void autoClosePreviousContext() {
        detector.startContext("ctx-1");
        detector.recordQuery("SELECT 1", null, 0);

        // Start a new context without closing the first
        detector.startContext("ctx-2");
        detector.recordQuery("SELECT 2", null, 0);

        NPlusOneReport report = detector.endContext();

        // Should report on ctx-2
        assertThat(report.getContextName()).isEqualTo("ctx-2");
        assertThat(report.getTotalQueryCount()).isEqualTo(1);
        assertThat(detector.getTotalContextsProcessed()).isEqualTo(2);
    }
}
