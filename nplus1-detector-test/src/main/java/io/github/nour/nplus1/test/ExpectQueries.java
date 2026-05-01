package io.github.nour.nplus1.test;

import java.lang.annotation.*;

/**
 * Asserts that the annotated test method executes a specific number
 * of SQL queries. Useful for ensuring query count doesn't regress.
 *
 * <h3>Usage:</h3>
 * <pre>
 * &#64;Test
 * &#64;ExpectQueries(count = 2) // Exactly 2 queries expected
 * void findUserWithOrders_shouldExecuteTwoQueries() {
 *     userRepository.findUserWithOrders(1L);
 * }
 *
 * &#64;Test
 * &#64;ExpectQueries(max = 5) // At most 5 queries
 * void batchOperation_shouldBeBounded() {
 *     batchService.processAll();
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExpectQueries {

    /**
     * Exact number of queries expected. Set to -1 to disable exact matching.
     * Cannot be used together with min/max.
     */
    int count() default -1;

    /**
     * Minimum number of queries expected. Default: 0 (no minimum).
     */
    int min() default 0;

    /**
     * Maximum number of queries expected. Default: Integer.MAX_VALUE (no maximum).
     */
    int max() default Integer.MAX_VALUE;

    /**
     * If true, only counts SELECT queries. Default: false (counts all).
     */
    boolean selectOnly() default false;
}
