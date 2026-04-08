package dev.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class MetricsTest extends ApiTestSupport {

    private String scrape() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "");   // Prometheus scrapes without an API key
        var response = http.exchange("/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    void businessAndHttpMetricsAreExposedForPrometheus() throws InterruptedException {
        long asha = newWalletWith(10_000);
        long ravi = newWallet();
        post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 4_000));
        post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 999_999));   // declined
        String key = UUID.randomUUID().toString();
        post("/v1/topups", Map.of("accountId", ravi, "amount", 100), key);
        post("/v1/topups", Map.of("accountId", ravi, "amount", 100), key);                            // replay

        // The publish-lag timer appears once the relay (every 500 ms) has sent something.
        String metrics = scrape();
        for (int i = 0; i < 50 && !metrics.contains("ledger_outbox_publish_lag_seconds_bucket"); i++) {
            Thread.sleep(200);
            metrics = scrape();
        }

        assertThat(metrics)
                .contains("ledger_entries_posted_total{application=\"ledger-service\",type=\"TRANSFER\"}")
                .contains("ledger_money_moved_paise_total{application=\"ledger-service\",type=\"TOPUP\"}")
                .contains("ledger_entries_declined_total{application=\"ledger-service\",reason=\"insufficient_funds\"}")
                .contains("ledger_idempotency_replays_total{application=\"ledger-service\",source=\"redis\"}")
                .contains("ledger_outbox_pending{application=\"ledger-service\"}")
                .contains("ledger_outbox_oldest_pending_age_seconds")
                .contains("ledger_outbox_publish_lag_seconds_bucket")
                .contains("ledger_webhooks_pending")
                .contains("ledger_db_deadlocks{application=\"ledger-service\"} 0.0")
                .contains("http_server_requests_seconds_bucket{application=\"ledger-service\",error=\"none\",exception=\"none\",method=\"POST\"")
                .contains("uri=\"/v1/transfers\"");
    }
}
