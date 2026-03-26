package dev.ledger.idempotency;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis copy of finished idempotency results, so most retries are answered without touching Postgres.
 * <p>
 * Only COMPLETED results are cached, and they never change, so the cache can't be stale, only missing.
 * Every failure is swallowed: if Redis is down or slow the caller falls back to Postgres, which is the
 * real guarantee.
 */
@Component
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);
    static final String PREFIX = "idem:";

    /** A finished request: the hash it was made with and the response it got. */
    public record CachedResult(String requestHash, int status, String body) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public IdempotencyCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public Optional<CachedResult> get(String key) {
        try {
            String value = redis.opsForValue().get(PREFIX + key);
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, CachedResult.class));
        } catch (Exception e) {
            log.warn("Idempotency cache read failed for key {}; falling back to Postgres: {}", key, e.toString());
            return Optional.empty();
        }
    }

    public void put(String key, CachedResult result, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + key, json.writeValueAsString(result), ttl);
        } catch (Exception e) {
            log.warn("Idempotency cache write failed for key {}: {}", key, e.toString());
        }
    }
}
