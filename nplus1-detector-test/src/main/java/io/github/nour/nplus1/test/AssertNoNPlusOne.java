package io.github.nour.nplus1.test;

import java.lang.annotation.*;

/**
 * Asserts that the annotated test method does NOT trigger any N+1 queries.
 *
 * <p>When combined with {@link NPlusOneDetectorExtension}, this annotation
 * causes the test to fail if any N+1 patterns are detected during execution.
 *
 * <h3>Usage:</h3>
 * <pre>
 * &#64;Test
 * &#64;AssertNoNPlusOne
 * void findAllUsers_shouldNotCauseNPlusOne() {
 *     userRepository.findAll().forEach(User::getOrders);
 *     // Test FAILS if N+1 is detected
 * }
 * </pre>
 *
 * <p>You can customize the detection threshold:
 * <pre>
 * &#64;Test
 * &#64;AssertNoNPlusOne(threshold = 5) // Allow up to 4 duplicate queries
 * void findUsers_withRelaxedThreshold() {
 *     // ...
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AssertNoNPlusOne {

    /**
     * Minimum duplicate queries to consider as N+1.
     * Default: 2 (strict — even 2 duplicate queries fail).
     */
    int threshold() default 2;
}
