package dev.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class IdempotencyApiTest extends ApiTestSupport {

    @Autowired
    RequestFingerprint fingerprint;

    private long entriesWithKey(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = :k").param("k", key)
                .query(Long.class).single();
    }

    private String transferJson(long from, long to, long amount) {
        return "{\"fromAccountId\":" + from + ",\"toAccountId\":" + to + ",\"amount\":" + amount + "}";
    }

    @Test
    void retryWithSameKeyReplaysTheOriginalResponseAndMovesMoneyOnce() {
        long asha = newWalletWith(100_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();
        var body = Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 30_000);

        var first = post("/v1/transfers", body, key);
        var retry = post("/v1/transfers", body, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(balanceOf(asha)).isEqualTo(70_000);
        assertThat(balanceOf(ravi)).isEqualTo(30_000);
        assertThat(entriesWithKey(key)).isEqualTo(1);
    }

    @Test
    void reorderedKeysAndWhitespaceCountAsTheSameRequest() {
        long asha = newWalletWith(100_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();

        var first = postRaw("/v1/transfers", transferJson(asha, ravi, 1_000), key);
        var retry = postRaw("/v1/transfers",
                "{ \"amount\": 1000,\n  \"toAccountId\": " + ravi + ", \"fromAccountId\": " + asha + " }", key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balanceOf(asha)).isEqualTo(99_000);
    }

    @Test
    void sameKeyWithDifferentBodyIs422AndDoesNothing() {
        long asha = newWalletWith(100_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();
        post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 1_000), key);

        var reused = post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 2_000), key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reused.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(balanceOf(asha)).isEqualTo(99_000);
    }

    @Test
    void sameKeyOnAnotherEndpointIs422() {
        long asha = newWalletWith(100_000);
        String key = UUID.randomUUID().toString();
        post("/v1/topups", Map.of("accountId", asha, "amount", 500), key);

        var reused = post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", newWallet(), "amount", 500), key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void requestStillRunningIs409() {
        long asha = newWalletWith(100_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();
        String body = transferJson(asha, ravi, 1_000);
        insertClaim(key, fingerprint.of("POST", "/v1/transfers", body.getBytes(StandardCharsets.UTF_8)), "now()");

        var response = postRaw("/v1/transfers", body, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "REQUEST_IN_PROGRESS");
        assertThat(balanceOf(asha)).isEqualTo(100_000);
    }

    @Test
    void claimAbandonedByACrashedServerIsTakenOverAfterItsLease() {
        // An IN_PROGRESS row never commits money (the transfer and COMPLETED commit together),
        // so once its 60 s lease is gone a retry may safely run the request.
        long asha = newWalletWith(100_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();
        String body = transferJson(asha, ravi, 1_000);
        insertClaim(key, fingerprint.of("POST", "/v1/transfers", body.getBytes(StandardCharsets.UTF_8)),
                "now() - interval '5 minutes'");

        var retry = postRaw("/v1/transfers", body, key);
        var again = postRaw("/v1/transfers", body, key);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balanceOf(asha)).isEqualTo(99_000);
        assertThat(entriesWithKey(key)).isEqualTo(1);
    }

    @Test
    void insufficientFundsIsRememberedForThatKey() {
        long asha = newWalletWith(1_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();
        var body = Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 5_000);

        assertThat(post("/v1/transfers", body, key).getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        post("/v1/topups", Map.of("accountId", asha, "amount", 10_000));
        var retry = post("/v1/transfers", body, key);

        // Same key means same operation: it was declined, so it stays declined. A new attempt needs a new key.
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balanceOf(asha)).isEqualTo(11_000);
    }

    @Test
    void validationErrorReleasesTheKeySoTheClientCanFixAndRetry() {
        long asha = newWalletWith(100_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();

        var invalid = post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", -5), key);
        var fixed = post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 500), key);

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fixed.getHeaders().getFirst("Idempotent-Replayed")).isNull();
    }

    @Test
    void keyLongerThan255CharsIs400() {
        long asha = newWalletWith(1_000);

        var response = post("/v1/topups", Map.of("accountId", asha, "amount", 100), "k".repeat(256));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "INVALID_IDEMPOTENCY_KEY");
    }

    private void insertClaim(String key, String hash, String lockedAtSql) {
        jdbc.sql("INSERT INTO idempotency_keys (key, request_hash, status, claim_token, locked_at) "
                        + "VALUES (:key, :hash, 'IN_PROGRESS', :token, " + lockedAtSql + ")")
                .param("key", key)
                .param("hash", hash)
                .param("token", UUID.randomUUID())
                .update();
    }
}
