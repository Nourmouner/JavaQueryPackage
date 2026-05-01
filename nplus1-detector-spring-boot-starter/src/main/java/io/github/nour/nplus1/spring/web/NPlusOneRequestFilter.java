package io.github.nour.nplus1.spring.web;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import io.github.nour.nplus1.core.report.ConsoleReporter;
import io.github.nour.nplus1.core.report.JsonReporter;
import io.github.nour.nplus1.core.report.NPlusOneReport;
import io.github.nour.nplus1.core.report.Reporter;
import io.github.nour.nplus1.spring.autoconfigure.NPlusOneProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;

/**
 * Servlet filter that creates an N+1 detection context per HTTP request.
 *
 * <p>Automatically wraps each incoming request in a detection context,
 * so all database queries within the request are tracked and analyzed.
 *
 * <p>Runs early in the filter chain (high precedence) to capture all queries,
 * including those in other filters.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class NPlusOneRequestFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneRequestFilter.class);

    private final NPlusOneDetector detector;
    private final NPlusOneProperties properties;
    private final Reporter reporter;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public NPlusOneRequestFilter(NPlusOneDetector detector, NPlusOneProperties properties) {
        this.detector = detector;
        this.properties = properties;
        this.reporter = createReporter(properties);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        String path = httpRequest.getRequestURI();

        // Skip excluded paths
        if (isExcludedPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        String contextName = httpRequest.getMethod() + " " + path;

        detector.startContext(contextName);
        try {
            chain.doFilter(request, response);
        } finally {
            NPlusOneReport report = detector.endContext();

            // Report results
            if (report.hasViolations()) {
                reporter.report(report);

                // Add HTTP response headers in development mode
                if (response instanceof jakarta.servlet.http.HttpServletResponse httpResponse) {
                    if (!httpResponse.isCommitted()) {
                        httpResponse.addHeader("X-NPlus1-Violations",
                                String.valueOf(report.getViolationCount()));
                        httpResponse.addHeader("X-NPlus1-Total-Queries",
                                String.valueOf(report.getTotalQueryCount()));
                        httpResponse.addHeader("X-NPlus1-Wasted-Time-Ms",
                                String.valueOf(report.getTotalWastedTimeMs()));
                    }
                }
            }
        }
    }

    private boolean isExcludedPath(String path) {
        List<String> excludePaths = properties.getExcludePaths();
        if (excludePaths == null || excludePaths.isEmpty()) return false;

        return excludePaths.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Reporter createReporter(NPlusOneProperties properties) {
        return switch (properties.getReportFormat()) {
            case CONSOLE -> new ConsoleReporter(properties.isIncludeCodeExamples());
            case JSON -> new JsonReporter();
            case BOTH -> report -> {
                new ConsoleReporter(properties.isIncludeCodeExamples()).report(report);
                new JsonReporter().report(report);
            };
        };
    }
}
