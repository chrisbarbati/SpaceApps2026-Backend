package org.greenalienhouseparty.config;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds a validated request ID to responses and logs a compact request summary.
 *
 * <p>Safe caller-provided IDs are reused; missing or unsafe IDs are replaced
 * with a random UUID. The ID is placed in MDC so downstream client calls can
 * propagate it to the Python data backend.</p>
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    /** Header used to correlate requests across the frontend, Java and Python services. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MDC key under which the current request ID is stored. */
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    /**
     * Establishes a request ID, adds it to the response, and logs method, path,
     * status and duration without logging query values or request bodies.
     *
     * @param request     incoming servlet request
     * @param response    outgoing servlet response
     * @param filterChain remaining servlet filter chain
     * @throws ServletException if a downstream servlet fails
     * @throws IOException      if request processing fails
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        log.trace("Entering doFilterInternal()");
        String requestId = requestId(request.getHeader(REQUEST_ID_HEADER));
        String previousRequestId = MDC.get(MDC_KEY);
        long startedAt = System.nanoTime();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            double elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0;
            log.info("{} {} {} {} {}ms",
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    String.format(Locale.ROOT, "%.1f", elapsedMs));
            if (previousRequestId == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousRequestId);
            }
            log.trace("Exiting doFilterInternal()");
        }
    }

    /**
     * Reuses an allowed caller ID or generates a safe random ID.
     *
     * @param incoming caller-provided request ID, possibly {@code null}
     * @return a safe request ID to use for the current request
     */
    private String requestId(String incoming) {
        log.trace("Entering requestId()");
        try {
            if (incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()) {
                return incoming;
            }
            return UUID.randomUUID().toString().replace("-", "");
        } finally {
            log.trace("Exiting requestId()");
        }
    }
}
