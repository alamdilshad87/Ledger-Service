package dev.ledger.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes unpublished outbox rows to Kafka, oldest first.
 * <p>
 * Each batch runs in one transaction that locks its rows with {@code FOR UPDATE SKIP LOCKED}: a second
 * relay instance skips rows the first is already sending instead of waiting or sending them twice.
 * A row is stamped {@code published_at} only after Kafka acknowledges it. If the app dies after the ack
 * but before the commit, the row is sent again on restart: delivery is <b>at least once</b>, so
 * consumers must ignore duplicates by {@code eventId}.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final OutboxProperties properties;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transaction,
                       OutboxProperties properties) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = transaction;
        this.properties = properties;
    }

    /** Sends up to one batch and returns how many events were published. */
    public int publishBatch() {
        Integer published = transaction.execute(tx -> {
            List<OutboxRow> rows = jdbc.sql("""
                            SELECT id, aggregate_id, event_type, payload::text AS payload
                            FROM outbox_events
                            WHERE published_at IS NULL
                            ORDER BY seq
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                            """)
                    .param("limit", properties.batchSize())
                    .query((rs, n) -> new OutboxRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("aggregate_id", UUID.class),
                            rs.getString("event_type"),
                            rs.getString("payload")))
                    .list();

            int sent = 0;
            for (OutboxRow row : rows) {
                try {
                    send(row);
                } catch (Exception e) {
                    // Stop at the first failure so later events don't overtake this one. Rows sent so far
                    // in this batch are still marked; the rest are retried on the next tick.
                    log.warn("Outbox publish failed at event {} ({}); will retry: {}", row.id(), row.eventType(), e.toString());
                    break;
                }
                // clock_timestamp(), not now(): now() is the transaction's start time, which would understate lag.
                jdbc.sql("UPDATE outbox_events SET published_at = clock_timestamp() WHERE id = :id")
                        .param("id", row.id()).update();
                sent++;
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    private void send(OutboxRow row) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(properties.topic(), row.aggregateId().toString(), row.payload());
        record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        // Wait for the broker's ack (acks=all) before marking the row as published.
        kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private record OutboxRow(UUID id, UUID aggregateId, String eventType, String payload) {
    }
}
