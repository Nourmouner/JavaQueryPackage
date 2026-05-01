package io.github.nour.nplus1.core.analyzer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Analyzes stack traces to find the application code responsible
 * for triggering a query. Filters out framework/library noise to
 * pinpoint the exact line in user code that caused the N+1.
 */
public class StackTraceAnalyzer {

    private static final Set<String> FRAMEWORK_PREFIXES = Set.of(
            "org.hibernate.",
            "org.springframework.",
            "org.apache.",
            "com.zaxxer.hikari.",
            "java.",
            "javax.",
            "jakarta.",
            "sun.",
            "com.sun.",
            "jdk.",
            "io.github.nour.nplus1.",
            "net.bytebuddy.",
            "org.aopalliance.",
            "com.mysql.",
            "org.postgresql.",
            "org.h2.",
            "org.mariadb.",
            "oracle.jdbc.",
            "com.microsoft.sqlserver."
    );

    /** Maximum stack trace depth to capture (performance guard). */
    private static final int MAX_CAPTURE_DEPTH = 50;

    private final List<String> applicationPackages;

    /**
     * @param applicationPackages base packages of the user's application
     *                            e.g., ["com.mycompany.myapp"]
     */
    public StackTraceAnalyzer(List<String> applicationPackages) {
        this.applicationPackages = applicationPackages != null
                ? List.copyOf(applicationPackages)
                : List.of();
    }

    /**
     * Captures the current stack trace, filtered to relevant frames only.
     * Limited to {@link #MAX_CAPTURE_DEPTH} frames for performance.
     */
    public StackTraceElement[] captureRelevantStackTrace() {
        StackTraceElement[] fullTrace = Thread.currentThread().getStackTrace();

        // Limit depth for performance
        StackTraceElement[] limited = fullTrace.length > MAX_CAPTURE_DEPTH
                ? Arrays.copyOf(fullTrace, MAX_CAPTURE_DEPTH)
                : fullTrace;

        return filterToRelevant(limited);
    }

    /**
     * Filters a stack trace down to application-relevant frames.
     */
    public StackTraceElement[] filterToRelevant(StackTraceElement[] fullTrace) {
        return Arrays.stream(fullTrace)
                .filter(this::isRelevantFrame)
                .toArray(StackTraceElement[]::new);
    }

    /**
     * Finds the most likely application code location that triggered
     * the query — the "root cause" frame.
     *
     * <p>Priority:
     * <ol>
     *   <li>First frame in declared application packages</li>
     *   <li>First non-framework, non-proxy frame</li>
     *   <li>First frame available</li>
     * </ol>
     */
    public StackTraceElement findOriginFrame(StackTraceElement[] stackTrace) {
        if (stackTrace == null || stackTrace.length == 0) return null;

        // Priority 1: frame in user's declared packages
        for (StackTraceElement frame : stackTrace) {
            if (isInApplicationPackage(frame)) {
                return frame;
            }
        }

        // Priority 2: first non-framework frame
        for (StackTraceElement frame : stackTrace) {
            if (!isFrameworkFrame(frame) && !isProxyFrame(frame)) {
                return frame;
            }
        }

        // Last resort
        return stackTrace[0];
    }

    /**
     * Returns a human-readable location string like:
     * "com.myapp.service.OrderService.findAll(OrderService.java:42)"
     */
    public String formatOrigin(StackTraceElement[] stackTrace) {
        StackTraceElement origin = findOriginFrame(stackTrace);
        if (origin == null) return "unknown";
        return String.format("%s.%s(%s:%d)",
                origin.getClassName(),
                origin.getMethodName(),
                origin.getFileName() != null ? origin.getFileName() : "Unknown",
                origin.getLineNumber());
    }

    /**
     * Extracts just the simple class name and method from the origin, e.g. "OrderService.findAll"
     */
    public String formatOriginShort(StackTraceElement[] stackTrace) {
        StackTraceElement origin = findOriginFrame(stackTrace);
        if (origin == null) return "unknown";
        String className = origin.getClassName();
        int lastDot = className.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
        return simpleName + "." + origin.getMethodName();
    }

    private boolean isRelevantFrame(StackTraceElement frame) {
        if (isInApplicationPackage(frame)) return true;
        return !isFrameworkFrame(frame) && !isProxyFrame(frame);
    }

    private boolean isInApplicationPackage(StackTraceElement frame) {
        String className = frame.getClassName();
        return applicationPackages.stream()
                .anyMatch(className::startsWith);
    }

    private boolean isFrameworkFrame(StackTraceElement frame) {
        String className = frame.getClassName();
        return FRAMEWORK_PREFIXES.stream()
                .anyMatch(className::startsWith);
    }

    private boolean isProxyFrame(StackTraceElement frame) {
        String className = frame.getClassName();
        return className.contains("$$") // CGLIB/ByteBuddy proxies
                || className.contains("$Proxy")
                || className.contains("FastClass")
                || className.contains("EnhancerBySpring");
    }
}
