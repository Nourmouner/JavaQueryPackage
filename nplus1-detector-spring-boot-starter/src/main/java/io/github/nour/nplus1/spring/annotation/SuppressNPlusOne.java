package io.github.nour.nplus1.spring.annotation;

import java.lang.annotation.*;

/**
 * Suppresses N+1 detection for the annotated method or class.
 *
 * <p>Use this when you've intentionally chosen a query pattern that
 * looks like N+1 but is actually optimal for your use case (e.g.,
 * batch processing with manual control).
 *
 * <h3>Usage:</h3>
 * <pre>
 * &#64;Service
 * public class DataMigrationService {
 *
 *     &#64;SuppressNPlusOne(reason = "Intentional batch processing with manual pagination")
 *     public void migrateData() {
 *         // This method will not trigger N+1 warnings
 *     }
 * }
 * </pre>
 *
 * <p>Can also be applied at the class level to suppress all methods:
 * <pre>
 * &#64;SuppressNPlusOne(reason = "Migration service - batch queries expected")
 * &#64;Service
 * public class BatchService {
 *     // All methods in this class will not trigger N+1 warnings
 * }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SuppressNPlusOne {

    /**
     * Reason for suppressing the N+1 warning.
     * Documenting the reason helps future developers understand
     * why the suppression is intentional.
     */
    String reason() default "";
}
