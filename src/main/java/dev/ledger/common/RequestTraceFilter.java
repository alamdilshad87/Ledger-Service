package dev.ledger.common;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter on every request: assigns a trace id, returns it as {@code X-Request-Id}, puts it in the
 * logging MDC (so every log line for this request carries it), and writes one access-log line at the end.
 * A caller-supplied {@code X-Request-Id} is reused if it looks sane, so ids can follow a request across services.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    private static final Logger access = LoggerFactory.getLogger("ledger.access");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(RequestIds.HEADER);
        String traceId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put(RequestIds.MDC_KEY, traceId);
        response.setHeader(RequestIds.HEADER, traceId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (!request.getRequestURI().startsWith("/actuator")) {   // skip health checks and metric scrapes
                access.info("{} {} {}", request.getMethod(), request.getRequestURI(), response.getStatus(),
                        kv("method", request.getMethod()),
                        kv("path", request.getRequestURI()),
                        kv("status", response.getStatus()),
                        kv("durationMs", (System.nanoTime() - start) / 1_000_000),
                        kv("client", request.getAttribute("ledger.clientName")));
            }
            MDC.clear();
        }
    }
}
