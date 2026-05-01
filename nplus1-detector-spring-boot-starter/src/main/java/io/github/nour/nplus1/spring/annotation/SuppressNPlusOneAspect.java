package io.github.nour.nplus1.spring.annotation;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AOP aspect that handles the {@link SuppressNPlusOne} annotation.
 *
 * <p>When a method or class is annotated with {@code @SuppressNPlusOne},
 * this aspect temporarily suspends N+1 detection for the duration
 * of that method execution.
 *
 * <p>It works by ending the current detection context before the method,
 * and starting a fresh one after — effectively creating a "hole" in the
 * detection timeline for the suppressed code.
 */
@Aspect
public class SuppressNPlusOneAspect {

    private static final Logger log = LoggerFactory.getLogger(SuppressNPlusOneAspect.class);

    /** Flag that tells the request filter to skip N+1 analysis. */
    private static final ThreadLocal<Boolean> suppressed = ThreadLocal.withInitial(() -> false);

    /**
     * Checks if N+1 detection is currently suppressed on this thread.
     */
    public static boolean isSuppressed() {
        return suppressed.get();
    }

    @Around("@annotation(suppressAnnotation)")
    public Object aroundMethod(ProceedingJoinPoint joinPoint, SuppressNPlusOne suppressAnnotation) throws Throwable {
        return executeWithSuppression(joinPoint, suppressAnnotation.reason());
    }

    @Around("@within(suppressAnnotation) && !@annotation(io.github.nour.nplus1.spring.annotation.SuppressNPlusOne)")
    public Object aroundClass(ProceedingJoinPoint joinPoint, SuppressNPlusOne suppressAnnotation) throws Throwable {
        return executeWithSuppression(joinPoint, suppressAnnotation.reason());
    }

    private Object executeWithSuppression(ProceedingJoinPoint joinPoint, String reason) throws Throwable {
        if (suppressed.get()) {
            // Already suppressed (nested call) — just proceed
            return joinPoint.proceed();
        }

        String methodName = joinPoint.getSignature().toShortString();
        log.debug("N+1 detection suppressed for {} (reason: {})", methodName,
                reason.isEmpty() ? "not specified" : reason);

        suppressed.set(true);
        try {
            return joinPoint.proceed();
        } finally {
            suppressed.set(false);
        }
    }
}
