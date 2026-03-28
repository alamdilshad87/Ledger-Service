package dev.ledger.idempotency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.common.GlobalExceptionHandler.ErrorResponse;
import dev.ledger.common.IdempotencyKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes every money-moving POST safe to retry.
 *
 * <pre>
 * same key + same request, finished      → replay the stored response (Idempotent-Replayed: true)
 * same key + same request, still running → 409 REQUEST_IN_PROGRESS
 * same key + different request           → 422 IDEMPOTENCY_KEY_REUSED
 * new key                                → run it once
 * </pre>
 *
 * The request runs inside one transaction that also marks the key COMPLETED, so the money movement and
 * the stored response commit together or not at all. There is no window where money moved but the key
 * still looks unfinished.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    /** POST endpoints that move money. Every one of them requires an Idempotency-Key. */
    static final List<String> PROTECTED_PATHS =
            List.of("/v1/topups", "/v1/transfers", "/v1/payments", "/v1/payments/*/refunds");

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyStore store;
    private final IdempotencyCache cache;
    private final RequestFingerprint fingerprint;
    private final IdempotencyProperties properties;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public IdempotencyFilter(IdempotencyStore store, IdempotencyCache cache, RequestFingerprint fingerprint,
                             IdempotencyProperties properties, TransactionTemplate transaction, ObjectMapper json) {
        this.store = store;
        this.cache = cache;
        this.fingerprint = fingerprint;
        this.properties = properties;
        this.transaction = transaction;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("POST".equals(request.getMethod()) && PROTECTED_PATHS.stream().anyMatch(p -> PATHS.match(p, path)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest httpRequest, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = httpRequest.getHeader(IdempotencyKeys.HEADER);
        if (key == null || key.isBlank()) {
            writeError(response, 400, "MISSING_IDEMPOTENCY_KEY", IdempotencyKeys.HEADER + " header is required");
            return;
        }
        if (key.length() > 255) {
            writeError(response, 400, "INVALID_IDEMPOTENCY_KEY", IdempotencyKeys.HEADER + " must be at most 255 characters");
            return;
        }

        CachedBodyRequest request = new CachedBodyRequest(httpRequest);
        String requestHash = fingerprint.of(request.getMethod(), request.getRequestURI(), request.body());

        // Fast path: a finished request is answered from Redis without touching Postgres.
        Optional<IdempotencyCache.CachedResult> cached = cache.get(key);
        if (cached.isPresent()) {
            if (cached.get().requestHash().equals(requestHash)) {
                replay(response, cached.get().status(), cached.get().body());
            } else {
                writeKeyReused(response);
            }
            return;
        }

        Optional<UUID> claim = store.tryClaim(key, requestHash, properties.lease());
        if (claim.isEmpty()) {
            respondToExistingKey(key, requestHash, response);
            return;
        }
        runOnce(key, requestHash, claim.get(), request, response, chain);
    }

    private void respondToExistingKey(String key, String requestHash, HttpServletResponse response) throws IOException {
        Optional<IdempotencyStore.KeyRecord> existing = store.find(key);
        if (existing.isEmpty()) {
            // The holder released the key between our claim attempt and this read.
            writeError(response, 409, "REQUEST_IN_PROGRESS", "A request with this key is being processed; retry shortly");
            return;
        }
        IdempotencyStore.KeyRecord record = existing.get();
        if (!record.requestHash().equals(requestHash)) {
            writeKeyReused(response);
        } else if (record.status() == IdempotencyStore.Status.IN_PROGRESS) {
            writeError(response, 409, "REQUEST_IN_PROGRESS", "A request with this key is being processed; retry shortly");
        } else {
            // Redis missed (evicted, restarted or down): refill it for the next retry.
            cache.put(key, new IdempotencyCache.CachedResult(record.requestHash(), record.responseCode(),
                    record.responseBody()), properties.ttl());
            replay(response, record.responseCode(), record.responseBody());
        }
    }

    private void writeKeyReused(HttpServletResponse response) throws IOException {
        writeError(response, 422, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different request");
    }

    private void runOnce(String key, String requestHash, UUID claim, CachedBodyRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        boolean completed;
        try {
            completed = Boolean.TRUE.equals(transaction.execute(tx -> {
                try {
                    chain.doFilter(request, buffered);
                } catch (IOException | ServletException e) {
                    throw new ChainFailure(e);
                }
                int status = buffered.getStatus();
                if (status >= 200 && status < 300) {
                    if (!store.complete(key, claim, status, bodyOf(buffered))) {
                        throw new LostClaimException();
                    }
                    return true;
                }
                // Business errors and validation failures move no money: roll back whatever ran.
                tx.setRollbackOnly();
                return false;
            }));
        } catch (LostClaimException e) {
            log.warn("Idempotency claim on key {} was taken over; rolled back this attempt", key);
            buffered.resetBuffer();
            writeError(buffered, 409, "REQUEST_IN_PROGRESS", "A request with this key is being processed; retry shortly");
            buffered.copyBodyToResponse();
            return;
        } catch (ChainFailure e) {
            store.release(key, claim);
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) e.getCause();
        } catch (RuntimeException e) {
            // e.g. the commit itself failed: the transaction rolled back, so no money moved.
            log.error("Request with Idempotency-Key {} failed; key released", key, e);
            store.release(key, claim);
            buffered.resetBuffer();
            writeError(buffered, 500, "INTERNAL_ERROR", "Something went wrong; retry with the same Idempotency-Key");
            buffered.copyBodyToResponse();
            return;
        }

        int status = buffered.getStatus();
        if (!completed) {
            if (isFinalBusinessOutcome(status)) {
                // Same key + same request must keep getting the same answer, even if the balance changes later.
                completed = store.complete(key, claim, status, bodyOf(buffered));
            } else {
                // 400s and 5xx: nothing happened, let the client retry with the same key.
                store.release(key, claim);
            }
        }
        if (completed) {
            // Only after the Postgres commit: Redis must never know a result Postgres doesn't.
            cache.put(key, new IdempotencyCache.CachedResult(requestHash, status, bodyOf(buffered)), properties.ttl());
        }
        buffered.copyBodyToResponse();
    }

    /** Deterministic refusals worth remembering: 402 insufficient funds, 404 unknown account, 422 rule broken. */
    private static boolean isFinalBusinessOutcome(int status) {
        return status == 402 || status == 404 || status == 422;
    }

    private void replay(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setHeader(REPLAYED_HEADER, "true");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), new ErrorResponse(code, message, UUID.randomUUID().toString()));
    }

    private static String bodyOf(ContentCachingResponseWrapper response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** Carries the chain's checked IOException or ServletException out of the transaction callback. */
    private static final class ChainFailure extends RuntimeException {
        ChainFailure(Exception cause) {
            super(cause);
        }
    }

    /** Our lease expired and another request with the same key took over; our work must not commit. */
    private static final class LostClaimException extends RuntimeException {
    }
}
