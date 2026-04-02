package dev.ledger.auth;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.common.ErrorResponses;
import dev.ledger.common.LedgerMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies the caller's rate limit after authentication; over the limit gets 429 with Retry-After. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final ObjectMapper json;
    private final LedgerMetrics metrics;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper json, LedgerMetrics metrics) {
        this.limiter = limiter;
        this.json = json;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(request.getAttribute(ApiClient.ATTRIBUTE) instanceof ApiClient client)) {
            chain.doFilter(request, response);   // unauthenticated paths (actuator, docs)
            return;
        }
        RateLimiter.Decision decision = limiter.tryAcquire(client);
        response.setHeader("X-RateLimit-Limit", String.valueOf(client.ratePerSecond()));
        if (decision.remaining() >= 0) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        }
        if (!decision.allowed()) {
            metrics.rateLimited(client.name());
            response.setHeader("Retry-After", String.valueOf(Math.max(1, (decision.retryAfterMs() + 999) / 1000)));
            ErrorResponses.write(response, json, 429, "RATE_LIMITED",
                    "Rate limit of " + client.ratePerSecond() + " requests/second exceeded");
            return;
        }
        chain.doFilter(request, response);
    }
}
