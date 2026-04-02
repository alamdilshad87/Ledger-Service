package dev.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

class IdempotencyCacheTest extends ApiTestSupport {

    @Autowired
    StringRedisTemplate redis;

    @Test
    void finishedRequestIsCachedInRedisWithATtl() {
        long asha = newWalletWith(10_000);
        String key = UUID.randomUUID().toString();

        post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", newWallet(), "amount", 100), key);

        assertThat(redis.opsForValue().get("idem:" + storedIdempotencyKey(key))).contains("\"status\":201");
        assertThat(redis.getExpire("idem:" + storedIdempotencyKey(key))).isBetween(23 * 3600L, 24 * 3600L);
    }

    @Test
    void retryIsAnsweredFromRedisWithoutPostgres() {
        long asha = newWalletWith(10_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();
        var body = Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 100);
        var first = post("/v1/transfers", body, key);

        // Remove the Postgres row: only Redis can answer now.
        jdbc.sql("DELETE FROM idempotency_keys WHERE key = :k").param("k", storedIdempotencyKey(key)).update();
        var retry = post("/v1/transfers", body, key);

        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(balanceOf(asha)).isEqualTo(9_900);
    }

    @Test
    void differentBodyIsRejectedOnTheFastPathToo() {
        long asha = newWalletWith(10_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();
        post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 100), key);

        var reused = post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 999), key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reused.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void redisMissFallsBackToPostgresAndRefillsTheCache() {
        long asha = newWalletWith(10_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();
        var body = Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 100);
        post("/v1/transfers", body, key);

        redis.delete("idem:" + storedIdempotencyKey(key));   // as if Redis restarted or evicted it
        var retry = post("/v1/transfers", body, key);

        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balanceOf(asha)).isEqualTo(9_900);
        assertThat(redis.hasKey("idem:" + storedIdempotencyKey(key))).isTrue();
    }
}
