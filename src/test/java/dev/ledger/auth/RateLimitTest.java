package dev.ledger.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

class RateLimitTest extends ApiTestSupport {

    private String keyWithLimit(int ratePerSecond, int burst) {
        return (String) post("/v1/admin/api-keys",
                Map.of("name", "limited", "role", "CLIENT", "ratePerSecond", ratePerSecond, "burst", burst))
                .getBody().get("key");
    }

    private ResponseEntity<Map<String, Object>> getAs(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(key);
        return http.exchange("/v1/accounts/1", HttpMethod.GET, new HttpEntity<>(headers), JSON);
    }

    @Test
    void burstIsAllowedThenRequestsGet429WithRetryAfter() {
        String key = keyWithLimit(1, 3);

        List<Integer> statuses = new ArrayList<>();
        ResponseEntity<Map<String, Object>> last = null;
        for (int i = 0; i < 5; i++) {
            last = getAs(key);
            statuses.add(last.getStatusCode().value());
        }

        assertThat(statuses.subList(0, 3)).containsOnly(200);
        assertThat(statuses.get(4)).isEqualTo(429);
        assertThat(last.getBody()).containsEntry("code", "RATE_LIMITED");
        assertThat(last.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(last.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("1");
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        String key = keyWithLimit(2, 1);
        assertThat(getAs(key).getStatusCode().value()).isEqualTo(200);
        assertThat(getAs(key).getStatusCode().value()).isEqualTo(429);

        Thread.sleep(600);   // 2 tokens/second: one token back after 500 ms

        assertThat(getAs(key).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void eachKeyHasItsOwnBucket() {
        String noisy = keyWithLimit(1, 1);
        String quiet = keyWithLimit(1, 1);
        getAs(noisy);

        assertThat(getAs(noisy).getStatusCode().value()).isEqualTo(429);
        assertThat(getAs(quiet).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void remainingTokensAreReported() {
        String key = keyWithLimit(1, 5);

        assertThat(getAs(key).getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("4");
        assertThat(getAs(key).getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("3");
    }
}
