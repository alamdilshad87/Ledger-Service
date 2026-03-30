package dev.ledger.webhook;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns ledger events into pending webhook deliveries, one per active subscription of the event's merchant.
 * <p>
 * It only records what must be sent; {@link WebhookDeliveryWorker} does the HTTP calls. Keeping slow
 * merchant servers out of the Kafka consumer means one dead merchant can't stall the topic for everyone.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public WebhookDispatcher(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @KafkaListener(topics = "${ledger.outbox.topic:ledger-events}", groupId = "${ledger.webhooks.consumer-group:webhook-dispatcher}")
    public void onEvent(ConsumerRecord<String, String> record) {
        JsonNode event;
        UUID eventId;
        try {
            event = json.readTree(record.value());
            eventId = UUID.fromString(event.path("eventId").asText());
        } catch (Exception e) {
            // A malformed event will never parse; retrying would block the partition forever.
            log.error("Skipping unreadable event at {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), e.toString());
            return;
        }

        String merchantId = event.path("data").path("merchantId").asText(null);
        if (merchantId == null) {
            return;   // top-ups and transfers have no merchant to notify
        }

        // Database errors propagate so the Kafka error handler retries the record with backoff.
        int created = jdbc.sql("""
                        INSERT INTO webhook_deliveries (id, event_id, event_type, subscription_id, payload)
                        SELECT gen_random_uuid(), :eventId, :eventType, s.id, :payload
                        FROM webhook_subscriptions s
                        WHERE s.merchant_id = CAST(:merchantId AS uuid) AND s.active
                        ON CONFLICT (event_id, subscription_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("eventType", event.path("eventType").asText())
                .param("merchantId", merchantId)
                .param("payload", record.value())
                .update();
        if (created > 0) {
            log.debug("Queued {} webhook deliveries for event {}", created, eventId);
        }
    }
}
