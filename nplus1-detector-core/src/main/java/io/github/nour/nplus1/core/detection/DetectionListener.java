package io.github.nour.nplus1.core.detection;

/**
 * Listener interface for N+1 detection events.
 *
 * <p>Implement this to customize behavior when violations are detected.
 * Multiple listeners can be registered with the detector — they will
 * all be called for each event.
 */
public interface DetectionListener {

    /**
     * Called when an N+1 violation is detected.
     * Implementations should be non-blocking and fast.
     *
     * @param violation the detected violation with full context
     */
    void onViolationDetected(NPlusOneViolation violation);

    /**
     * Called when a detection context completes with no violations found.
     *
     * @param context the clean context
     */
    default void onCleanContext(DetectionContext context) {
        // no-op by default
    }

    /**
     * Called when an error occurs during detection.
     * Default implementation ignores errors.
     *
     * @param context the context where the error occurred (may be null)
     * @param error   the error
     */
    default void onDetectionError(DetectionContext context, Throwable error) {
        // no-op by default
    }
}
