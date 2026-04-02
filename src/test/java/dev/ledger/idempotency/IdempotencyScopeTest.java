package dev.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class IdempotencyScopeTest extends ApiTestSupport {

    private String clientKey(String name) {
        return (String) post("/v1/admin/api-keys", Map.of("name", name, "role", "CLIENT", "ratePerSecond", 100, "burst", 100))
                .getBody().get("key");
    }

    private ResponseEntity<Map<String, Object>> topUpAs(String apiKey, long wallet, long amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange("/v1/topups", HttpMethod.POST,
                new HttpEntity<>(Map.of("accountId", wallet, "amount", amount), headers), JSON);
    }

    @Test
    void twoClientsUsingTheSameKeyDoNotCollide() {
        String partnerA = clientKey("partner-a");
        String partnerB = clientKey("partner-b");
        long walletA = newWallet();
        long walletB = newWallet();

        // Both partners' code happens to use "order-1001" as the key, with different requests.
        var a = topUpAs(partnerA, walletA, 1_000, "order-1001");
        var b = topUpAs(partnerB, walletB, 2_000, "order-1001");

        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).as("not a 422 key-reused").isEqualTo(HttpStatus.CREATED);
        assertThat(b.getHeaders().getFirst("Idempotent-Replayed")).as("not A's replayed response").isNull();
        assertThat(balanceOf(walletA)).isEqualTo(1_000);
        assertThat(balanceOf(walletB)).isEqualTo(2_000);
    }

    @Test
    void sameClientRetryingIsStillReplayed() {
        String partner = clientKey("partner-c");
        long wallet = newWallet();

        topUpAs(partner, wallet, 500, "retry-me");
        var retry = topUpAs(partner, wallet, 500, "retry-me");

        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balanceOf(wallet)).isEqualTo(500);
    }
}
