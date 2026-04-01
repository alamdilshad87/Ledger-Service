package dev.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

class RequestTraceTest extends ApiTestSupport {

    @Test
    void everyResponseCarriesARequestIdAndErrorsReuseIt() {
        var response = get("/v1/accounts/999999999");

        String requestId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).isNotBlank();
        assertThat(response.getBody()).containsEntry("traceId", requestId);
    }

    @Test
    void sensibleCallerSuppliedIdIsKept() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "checkout-7f3c9a2e");

        var response = http.exchange("/v1/accounts/999999999", HttpMethod.GET, new HttpEntity<>(headers), JSON);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("checkout-7f3c9a2e");
        assertThat(response.getBody()).containsEntry("traceId", "checkout-7f3c9a2e");
    }

    @Test
    void unsafeCallerSuppliedIdIsReplaced() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "bad id\" with quotes");

        var response = http.exchange("/v1/accounts/999999999", HttpMethod.GET, new HttpEntity<>(headers), JSON);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).doesNotContain("bad").hasSize(36);
    }

    @Test
    void outboxEventsCarryTheTraceIdOfTheRequestThatCausedThem() {
        long wallet = newWallet();
        var topUp = post("/v1/topups", Map.of("accountId", wallet, "amount", 500));
        String requestId = topUp.getHeaders().getFirst("X-Request-Id");

        String payload = jdbc.sql("SELECT payload ->> 'traceId' FROM outbox_events WHERE aggregate_id = CAST(:id AS uuid)")
                .param("id", topUp.getBody().get("entryId")).query(String.class).single();
        assertThat(payload).isEqualTo(requestId);
    }
}
