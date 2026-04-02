package dev.ledger.auth;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Per-API-key token bucket in Redis ({@code ratelimit/token_bucket.lua}).
 * <p>
 * If Redis is unreachable the request is <b>allowed</b> (fail open): a rate limiter that takes payments
 * down with it would be worse than a few minutes without limits. Idempotency and locking still protect
 * correctness; the limiter only protects capacity.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    public record Decision(boolean allowed, long remaining, long retryAfterMs) {
        static Decision failedOpen() {
            return new Decision(true, -1, 0);
        }
    }

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("ratelimit/token_bucket.lua"));
        this.script.setResultType(List.class);
    }

    public Decision tryAcquire(ApiClient client) {
        try {
            List<?> result = redis.execute(script, List.of("ratelimit:" + client.id()),
                    String.valueOf(client.burst()),
                    String.valueOf(client.ratePerSecond() / 1000.0),
                    "1");
            return new Decision(((Number) result.get(0)).longValue() == 1,
                    ((Number) result.get(1)).longValue(),
                    ((Number) result.get(2)).longValue());
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing request for {}: {}", client.name(), e.toString());
            return Decision.failedOpen();
        }
    }
}
