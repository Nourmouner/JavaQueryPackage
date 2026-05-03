package io.github.nour.nplus1.spring.annotation;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;

@Aspect
public class SuppressNPlusOneAspect {

    private static final Logger log = LoggerFactory.getLogger(SuppressNPlusOneAspect.class);
    private static final ThreadLocal<Boolean> suppressed = ThreadLocal.withInitial(() -> false);

    private final NPlusOneDetector detector;

    public SuppressNPlusOneAspect(NPlusOneDetector detector) {
        this.detector = detector;
    }

    /** Wires this aspect's flag into the detector so recordQuery can honor it. */
    @PostConstruct
    public void register() {
        detector.setSuppressionCheck(suppressed::get);
    }

    public static boolean isSuppressed() { return suppressed.get(); }

    @Around("@annotation(suppressAnnotation)")
    public Object aroundMethod(ProceedingJoinPoint jp, SuppressNPlusOne suppressAnnotation) throws Throwable {
        return executeWithSuppression(jp, suppressAnnotation.reason());
    }

    @Around("@within(suppressAnnotation) && !@annotation(io.github.nour.nplus1.spring.annotation.SuppressNPlusOne)")
    public Object aroundClass(ProceedingJoinPoint jp, SuppressNPlusOne suppressAnnotation) throws Throwable {
        return executeWithSuppression(jp, suppressAnnotation.reason());
    }

    private Object executeWithSuppression(ProceedingJoinPoint jp, String reason) throws Throwable {
        if (Boolean.TRUE.equals(suppressed.get())) return jp.proceed();
        log.debug("N+1 detection suppressed for {} (reason: {})",
                jp.getSignature().toShortString(), reason.isEmpty() ? "n/a" : reason);
        suppressed.set(true);
        try { return jp.proceed(); }
        finally { suppressed.set(false); }
    }
}