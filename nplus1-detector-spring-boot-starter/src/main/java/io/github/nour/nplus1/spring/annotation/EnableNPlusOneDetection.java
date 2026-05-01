package io.github.nour.nplus1.spring.annotation;

import java.lang.annotation.*;

/**
 * Enables N+1 query detection in a Spring Boot application.
 *
 * <p>This annotation is <b>optional</b> — the auto-configuration activates
 * automatically when the starter is on the classpath. Use this annotation
 * only if you want to make the detection activation explicit in code.
 *
 * <h3>Usage:</h3>
 * <pre>
 * &#64;SpringBootApplication
 * &#64;EnableNPlusOneDetection
 * public class MyApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyApplication.class, args);
 *     }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnableNPlusOneDetection {
}
