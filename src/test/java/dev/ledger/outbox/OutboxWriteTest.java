package dev.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.IllegalTransactionStateException;

class OutboxWriteTest extends ApiTestSupport {

    @Autowired
    OutboxWriter outbox;

    private List<String> eventTypesFor(Object aggregateId) {
        return jdbc.sql("SELECT event_type FROM outbox_events WHERE aggregate_id = CAST(:id AS uuid) ORDER BY seq")
                .param("id", aggregateId.toString())
                .query(String.class)
                .list();
    }

    @Test
    void transferWritesOneEventWithTheFullEnvelope() {
        long asha = newWalletWith(10_000);
        long ravi = newWallet();

        var response = post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 2_500));
        String entryId = (String) response.getBody().get("entryId");

        assertThat(eventTypesFor(entryId)).containsExactly("transfer.completed");
        String payload = jdbc.sql("SELECT payload::text FROM outbox_events WHERE aggregate_id = CAST(:id AS uuid)")
                .param("id", entryId).query(String.class).single();
        assertThat(payload).contains("\"eventId\"", "\"occurredAt\"", "\"amount\": 2500", "\"toAccountId\": " + ravi);
    }

    @Test
    void paymentAndItsRefundsShareOneAggregateInOrder() {
        long asha = newWalletWith(10_000);
        UUID payment = pay(asha, newMerchant(), 5_000);
        post("/v1/payments/" + payment + "/refunds", Map.of("amount", 1_000));
        post("/v1/payments/" + payment + "/refunds", Map.of());

        assertThat(eventTypesFor(payment)).containsExactly("payment.completed", "refund.completed", "refund.completed");
    }

    @Test
    void declinedTransferWritesNoEvent() {
        long asha = newWalletWith(1_000);
        long before = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'transfer.completed'")
                .query(Long.class).single();

        var response = post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", newWallet(), "amount", 5_000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'transfer.completed'")
                .query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void writingAnEventOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outbox.append("test", UUID.randomUUID(), "test.event", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
