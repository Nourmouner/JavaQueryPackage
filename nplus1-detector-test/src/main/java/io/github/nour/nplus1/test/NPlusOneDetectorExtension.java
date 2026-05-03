package io.github.nour.nplus1.test;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import io.github.nour.nplus1.core.detection.NPlusOneViolation;
import io.github.nour.nplus1.core.report.ConsoleReporter;
import io.github.nour.nplus1.core.report.NPlusOneReport;
import org.junit.jupiter.api.extension.*;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

public class NPlusOneDetectorExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(NPlusOneDetectorExtension.class);
    private static final String DETECTOR_KEY = "detector";
    private static final String OWNED_KEY    = "owned";
    private static final ConsoleReporter REPORTER = new ConsoleReporter(true);

    @Override
    public void beforeEach(ExtensionContext context) {
        int threshold = 2;
        AssertNoNPlusOne assertion = context.getRequiredTestMethod().getAnnotation(AssertNoNPlusOne.class);
        if (assertion != null) threshold = assertion.threshold();

        NPlusOneDetector detector = resolveSpringDetector(context);
        boolean owned = false;
        if (detector == null) {
            // Standalone (non-Spring) test — build a private detector
            detector = NPlusOneDetector.builder()
                    .threshold(threshold)
                    .captureStackTraces(true)
                    .build();
            owned = true;
        }
        context.getStore(NS).put(DETECTOR_KEY, detector);
        context.getStore(NS).put(OWNED_KEY, owned);
        detector.startContext("TEST: " + context.getDisplayName());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        NPlusOneDetector detector = context.getStore(NS).get(DETECTOR_KEY, NPlusOneDetector.class);
        if (detector == null) return;
        NPlusOneReport report = detector.endContext();

        AssertNoNPlusOne assertion = context.getRequiredTestMethod().getAnnotation(AssertNoNPlusOne.class);
        if (assertion != null) assertNoNPlusOne(report, context.getDisplayName());

        ExpectQueries expect = context.getRequiredTestMethod().getAnnotation(ExpectQueries.class);
        if (expect != null) assertQueryCount(report, expect, context.getDisplayName());
    }

    private NPlusOneDetector resolveSpringDetector(ExtensionContext context) {
        try {
            // If SpringExtension is registered, fetch the managed detector bean.
            ApplicationContext ac = SpringExtension.getApplicationContext(context);
            return ac.getBeanProvider(NPlusOneDetector.class).getIfAvailable();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void assertNoNPlusOne(NPlusOneReport report, String testName) {
        if (!report.hasViolations()) return;
        REPORTER.report(report);
        StringBuilder msg = new StringBuilder();
        msg.append("N+1 query violation detected in test: ").append(testName).append('\n');
        msg.append("Found ").append(report.getViolationCount()).append(" violation(s):\n");
        for (NPlusOneViolation v : report.getViolations()) {
            msg.append(String.format("  - [%s] %d duplicate queries on '%s' (origin: %s)%n",
                    v.getSeverity(), v.getOccurrences(),
                    truncate(v.getFingerprint(), 80), v.getCodeOrigin()));
            if (!v.getSuggestions().isEmpty())
                msg.append("    Fix: ").append(v.getSuggestions().get(0).getType().getShortName())
                        .append(" — ").append(v.getSuggestions().get(0).getExplanation()).append('\n');
        }
        throw new AssertionError(msg.toString());
    }

    private void assertQueryCount(NPlusOneReport report, ExpectQueries a, String testName) {
        int actual = report.getTotalQueryCount();
        if (a.count() >= 0) {
            if (actual != a.count())
                throw new AssertionError(String.format(
                        "Expected exactly %d queries in '%s', got %d.", a.count(), testName, actual));
            return;
        }
        if (actual < a.min())
            throw new AssertionError(String.format(
                    "Expected at least %d queries in '%s', got %d.", a.min(), testName, actual));
        if (actual > a.max())
            throw new AssertionError(String.format(
                    "Expected at most %d queries in '%s', got %d.", a.max(), testName, actual));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}