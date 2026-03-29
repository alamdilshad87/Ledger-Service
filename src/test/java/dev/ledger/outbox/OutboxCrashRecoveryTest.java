package dev.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

/**
 * Week 5 "done when": the app dies after the transfer commits but before the event is published.
 * Here the scheduled relay is switched off to freeze that moment; calling the relay by hand plays the
 * part of the restarted app. (docs/outbox.md has the same drill with a real process kill.)
 */
@TestPropertySource(properties = "ledger.outbox.relay.enabled=false")
class OutboxCrashRecoveryTest extends ApiTestSupport {

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    OutboxRelay relay;

    private boolean published(String aggregateId) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = CAST(:id AS uuid)")
                .param("id", aggregateId).query(Boolean.class).single();
    }

    @Test
    void eventCommittedButNotPublishedIsDeliveredAfterRestart() {
        long asha = newWalletWith(10_000);
        String entryId = (String) post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", newWallet(), "amount", 300))
                .getBody().get("entryId");

        try (KafkaEvents events = new KafkaEvents(bootstrapServers, "ledger-events")) {
            // "Crashed": the money moved and the event is in the outbox, but nothing reached Kafka.
            assertThat(balanceOf(asha)).isEqualTo(9_700);
            assertThat(published(entryId)).isFalse();
            assertThat(events.forKey(entryId, 1, Duration.ofSeconds(2))).isEmpty();

            // "Restarted": the relay finds the unpublished row and sends it.
            while (relay.publishBatch() > 0) {
                // drain the backlog, including events from earlier tests in this context
            }

            assertThat(events.forKey(entryId, 1, Duration.ofSeconds(15))).hasSize(1);
            assertThat(published(entryId)).isTrue();
        }
    }

    @Test
    void publishedEventsAreNotSentAgain() {
        long asha = newWalletWith(10_000);
        while (relay.publishBatch() > 0) {
            // drain
        }

        assertThat(relay.publishBatch()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single())
                .isZero();
        assertThat(balanceOf(asha)).isEqualTo(10_000);
    }
}
