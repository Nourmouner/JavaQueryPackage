package io.github.nour.nplus1.spring.web;

import io.github.nour.nplus1.core.detection.NPlusOneDetector;
import io.github.nour.nplus1.core.report.NPlusOneReport;
import io.github.nour.nplus1.core.report.Reporter;
import io.github.nour.nplus1.spring.autoconfigure.NPlusOneAutoConfiguration.NPlusOneDetectedException;
import io.github.nour.nplus1.spring.autoconfigure.NPlusOneProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;

@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class NPlusOneRequestFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneRequestFilter.class);

    private final NPlusOneDetector detector;
    private final NPlusOneProperties properties;
    private final Reporter reporter;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public NPlusOneRequestFilter(NPlusOneDetector detector,
                                 NPlusOneProperties properties,
                                 Reporter reporter) {
        this.detector = detector;
        this.properties = properties;
        this.reporter = reporter;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http)) {
            chain.doFilter(request, response);
            return;
        }
        if (isExcludedPath(http.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        detector.startContext(http.getMethod() + " " + http.getRequestURI());
        NPlusOneReport report = null;
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                report = detector.endContext();
            } catch (Exception e) {
                log.warn("N+1 endContext failed: {}", e.getMessage(), e);
            }
        }

        // Reporting + headers happen AFTER the chain returns, so we don't risk
        // throwing inside finally and corrupting the response.
        if (report != null && report.hasViolations()) {
            try { reporter.report(report); }
            catch (Exception e) { log.warn("Reporter failed: {}", e.getMessage(), e); }

            if (response instanceof HttpServletResponse http2 && !http2.isCommitted()) {
                http2.addHeader("X-NPlus1-Violations", String.valueOf(report.getViolationCount()));
                http2.addHeader("X-NPlus1-Total-Queries", String.valueOf(report.getTotalQueryCount()));
                http2.addHeader("X-NPlus1-Wasted-Time-Ms", String.valueOf(report.getTotalWastedTimeMs()));
                http2.addHeader("X-NPlus1-Severity", report.getHighestSeverity().name());
            }

            // THROW mode: only if we can still abort cleanly (response not committed).
            if (properties.getMode() == NPlusOneProperties.Mode.THROW
                    && response instanceof HttpServletResponse http2 && !http2.isCommitted()) {
                throw new NPlusOneDetectedException(report.getViolations().get(0));
            }
        }
    }

    private boolean isExcludedPath(String path) {
        List<String> excludes = properties.getExcludePaths();
        if (excludes == null || excludes.isEmpty()) return false;
        return excludes.stream().anyMatch(p -> pathMatcher.match(p, path));
    }
}