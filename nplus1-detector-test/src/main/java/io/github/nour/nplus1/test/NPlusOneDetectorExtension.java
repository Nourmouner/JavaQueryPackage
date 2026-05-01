package io.github.nour.nplus1.test;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import io.github.nour.nplus1.core.detection.NPlusOneViolation;
import io.github.nour.nplus1.core.model.QueryType;
import io.github.nour.nplus1.core.report.ConsoleReporter;
import io.github.nour.nplus1.core.report.NPlusOneReport;
import org.junit.jupiter.api.extension.*;

import java.util.List;

/**
 * JUnit 5 extension that integrates N+1 detection into your test lifecycle.
 *
 * <p>Supports:
 * <ul>
 *   <li>{@link AssertNoNPlusOne} — fail test if N+1 is detected</li>
 *   <li>{@link ExpectQueries} — assert exact/range query counts</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 * // Option 1: Register per-class
 * &#64;ExtendWith(NPlusOneDetectorExtension.class)
 * class UserServiceTest {
 *
 *     &#64;Test
 *     &#64;AssertNoNPlusOne
 *     void testNoNPlusOne() {
 *         // ...
 *     }
 * }
 *
 * // Option 2: Register globally in META-INF/services
 * // org.junit.jupiter.api.extension.Extension
 * </pre>
 *
 * <h3>With Spring Boot Test:</h3>
 * <pre>
 * &#64;SpringBootTest
 * &#64;ExtendWith(NPlusOneDetectorExtension.class)
 * class UserRepositoryIntegrationTest {
 *
 *     &#64;Autowired
 *     private UserRepository userRepository;
 *
 *     &#64;Test
 *     &#64;AssertNoNPlusOne
 *     &#64;ExpectQueries(max = 3)
 *     void findAll_shouldBeEfficient() {
 *         userRepository.findAll();
 *     }
 * }
 * </pre>
 */
public class NPlusOneDetectorExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(NPlusOneDetectorExtension.class);

    private static final String DETECTOR_KEY = "detector";
    private static final ConsoleReporter REPORTER = new ConsoleReporter(true);

    @Override
    public void beforeEach(ExtensionContext context) {
        // Determine threshold from annotation
        int threshold = 2; // default strict
        AssertNoNPlusOne assertAnnotation = context.getRequiredTestMethod()
                .getAnnotation(AssertNoNPlusOne.class);
        if (assertAnnotation != null) {
            threshold = assertAnnotation.threshold();
        }

        // Create a detector for this test
        NPlusOneDetector detector = NPlusOneDetector.builder()
                .threshold(threshold)
                .captureStackTraces(true)
                .build();

        // Store in extension context
        context.getStore(NAMESPACE).put(DETECTOR_KEY, detector);

        // Start a detection context for the test
        String testName = context.getDisplayName();
        detector.startContext("TEST: " + testName);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        NPlusOneDetector detector = context.getStore(NAMESPACE)
                .get(DETECTOR_KEY, NPlusOneDetector.class);

        if (detector == null) return;

        // End context and get report
        NPlusOneReport report = detector.endContext();

        // Check @AssertNoNPlusOne
        AssertNoNPlusOne assertAnnotation = context.getRequiredTestMethod()
                .getAnnotation(AssertNoNPlusOne.class);
        if (assertAnnotation != null) {
            assertNoNPlusOne(report, context.getDisplayName());
        }

        // Check @ExpectQueries
        ExpectQueries expectAnnotation = context.getRequiredTestMethod()
                .getAnnotation(ExpectQueries.class);
        if (expectAnnotation != null) {
            assertQueryCount(report, expectAnnotation, context.getDisplayName());
        }
    }

    // ─── Assertion Logic ─────────────────────────────────────────

    private void assertNoNPlusOne(NPlusOneReport report, String testName) {
        if (report.hasViolations()) {
            // Print the report for debugging
            REPORTER.report(report);

            StringBuilder message = new StringBuilder();
            message.append("N+1 query violation detected in test: ").append(testName).append('\n');
            message.append("Found ").append(report.getViolationCount()).append(" violation(s):\n");

            for (NPlusOneViolation violation : report.getViolations()) {
                message.append(String.format(
                        "  - [%s] %d duplicate queries on '%s' (origin: %s)%n",
                        violation.getSeverity(),
                        violation.getOccurrences(),
                        truncate(violation.getFingerprint(), 80),
                        violation.getCodeOrigin()
                ));

                if (!violation.getSuggestions().isEmpty()) {
                    message.append("    Fix: ")
                            .append(violation.getSuggestions().get(0).getType().getShortName())
                            .append(" — ")
                            .append(violation.getSuggestions().get(0).getExplanation())
                            .append('\n');
                }
            }

            throw new AssertionError(message.toString());
        }
    }

    private void assertQueryCount(NPlusOneReport report, ExpectQueries annotation, String testName) {
        int actualCount;

        if (annotation.selectOnly()) {
            // Count only SELECT queries from the violation history
            // For now, use total count as a proxy (full implementation
            // would track per-type counts in the report)
            actualCount = report.getTotalQueryCount();
        } else {
            actualCount = report.getTotalQueryCount();
        }

        // Exact count check
        if (annotation.count() >= 0) {
            if (actualCount != annotation.count()) {
                throw new AssertionError(String.format(
                        "Expected exactly %d queries in test '%s', but got %d.%n"
                        + "Total queries: %d | Violations: %d",
                        annotation.count(), testName, actualCount,
                        report.getTotalQueryCount(), report.getViolationCount()
                ));
            }
            return; // Exact count takes precedence
        }

        // Min check
        if (actualCount < annotation.min()) {
            throw new AssertionError(String.format(
                    "Expected at least %d queries in test '%s', but got %d.",
                    annotation.min(), testName, actualCount
            ));
        }

        // Max check
        if (actualCount > annotation.max()) {
            throw new AssertionError(String.format(
                    "Expected at most %d queries in test '%s', but got %d.%n"
                    + "This might indicate a query regression. Check for new N+1 issues.",
                    annotation.max(), testName, actualCount
            ));
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
