package dev.ledger.auth;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.common.ErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every {@code /v1/**} request needs {@code Authorization: Bearer <api key>}; {@code /v1/admin/**} needs an
 * ADMIN key. Runs right after the trace filter and before rate limiting and idempotency, which both use
 * the caller's identity. Actuator and API docs are outside {@code /v1} and stay open.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService keys;
    private final ObjectMapper json;

    public ApiKeyAuthFilter(ApiKeyService keys, ObjectMapper json) {
        this.keys = keys;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            response.setHeader("WWW-Authenticate", "Bearer");
            ErrorResponses.write(response, json, 401, "UNAUTHENTICATED", "Send Authorization: Bearer <api key>");
            return;
        }
        Optional<ApiClient> client = keys.authenticate(header.substring(7).trim());
        if (client.isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            ErrorResponses.write(response, json, 401, "INVALID_API_KEY", "API key is invalid or revoked");
            return;
        }
        if (request.getRequestURI().startsWith("/v1/admin/") && client.get().role() != ApiClient.Role.ADMIN) {
            ErrorResponses.write(response, json, 403, "FORBIDDEN", "This endpoint needs an admin API key");
            return;
        }

        request.setAttribute(ApiClient.ATTRIBUTE, client.get());
        request.setAttribute("ledger.clientName", client.get().name());   // for the access log
        MDC.put("client", client.get().name());
        chain.doFilter(request, response);
    }
}
