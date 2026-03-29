package dev.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** The scheduled relay is running in this context: events should reach Kafka within about a second. */
class OutboxRelayTest extends ApiTestSupport {

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Test
    void transferEventReachesKafkaWithKeyAndHeaders() {
        long asha = newWalletWith(10_000);
        long ravi = newWallet();
        String entryId = (String) post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 700))
                .getBody().get("entryId");

        try (KafkaEvents events = new KafkaEvents(bootstrapServers, "ledger-events")) {
            var records = events.forKey(entryId, 1, Duration.ofSeconds(15));

            assertThat(records).hasSize(1);
            var record = records.get(0);
            assertThat(KafkaEvents.header(record, "eventType")).isEqualTo("transfer.completed");
            assertThat(record.value()).contains("\"entryId\": \"" + entryId + "\"", "\"amount\": 700");
            String eventId = KafkaEvents.header(record, "eventId");
            assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = CAST(:id AS uuid)")
                    .param("id", eventId).query(Boolean.class).single()).isTrue();
        }
    }

    @Test
    void paymentAndRefundEventsArriveInOrderOnTheSameKey() {
        long asha = newWalletWith(10_000);
        UUID payment = pay(asha, newMerchant(), 4_000);
        post("/v1/payments/" + payment + "/refunds", Map.of("amount", 1_500));
        post("/v1/payments/" + payment + "/refunds", Map.of());

        try (KafkaEvents events = new KafkaEvents(bootstrapServers, "ledger-events")) {
            var records = events.forKey(payment.toString(), 3, Duration.ofSeconds(15));

            assertThat(records).extracting(r -> KafkaEvents.header(r, "eventType"))
                    .containsExactly("payment.completed", "refund.completed", "refund.completed");
        }
    }
}
