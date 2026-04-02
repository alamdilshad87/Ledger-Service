package dev.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

class IdempotencyCleanupTest extends ApiTestSupport {

    @Autowired
    IdempotencyCleanup cleanup;

    @Autowired
    StringRedisTemplate redis;

    private boolean exists(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM idempotency_keys WHERE key = :k").param("k", storedIdempotencyKey(key))
                .query(Long.class).single() == 1;
    }

    private void age(String key, String interval) {
        jdbc.sql("UPDATE idempotency_keys SET created_at = now() - CAST(:i AS interval) WHERE key = :k")
                .param("i", interval).param("k", storedIdempotencyKey(key)).update();
    }

    @Test
    void keysOlderThan24HoursAreDeletedAndFreshOnesKept() {
        long wallet = newWalletWith(10_000);
        String old = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        post("/v1/topups", Map.of("accountId", wallet, "amount", 100), old);
        post("/v1/topups", Map.of("accountId", wallet, "amount", 100), fresh);
        age(old, "25 hours");
        age(fresh, "23 hours");

        cleanup.purgeExpired();

        assertThat(exists(old)).isFalse();
        assertThat(exists(fresh)).isTrue();
    }

    @Test
    void anExpiredKeyCanBeUsedAgainAsANewRequest() {
        long wallet = newWalletWith(10_000);
        String key = UUID.randomUUID().toString();
        var body = Map.of("accountId", wallet, "amount", 100);
        post("/v1/topups", body, key);
        age(key, "25 hours");
        cleanup.purgeExpired();
        redis.delete("idem:" + storedIdempotencyKey(key));   // Redis would have expired it on its own by now

        var reused = post("/v1/topups", body, key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reused.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(balanceOf(wallet)).isEqualTo(10_200);
    }
}
