package dev.ledger.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import dev.ledger.common.LedgerMetrics;
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
    private final LedgerMetrics metrics;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transaction,
                       OutboxProperties properties, LedgerMetrics metrics) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = transaction;
        this.properties = properties;
        this.metrics = metrics;
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

            if (rows.isEmpty()) {
                return 0;
            }
            // Send the whole batch first, then wait for the acks. Waiting for each ack before the next send
            // capped the relay at one broker round trip per event (~200 events/s in the ramp load test).
            // Order is still kept per key: the producer is idempotent (enable.idempotence, acks=all), so
            // records to one partition land in send order even when retried, and a failed record fails
            // the ones queued behind it on that partition too.
            List<CompletableFuture<?>> acks = new ArrayList<>(rows.size());
            for (OutboxRow row : rows) {
                acks.add(send(row));
            }
            long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
            List<UUID> acked = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                OutboxRow row = rows.get(i);
                try {
                    acks.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (Exception e) {
                    // Mark only the acked prefix: this row and everything after it are retried on the next
                    // tick, in order. Later rows that did reach Kafka get sent again, which consumers
                    // already tolerate (at-least-once, deduped by eventId).
                    log.warn("Outbox publish failed at event {} ({}); will retry: {}", row.id(), row.eventType(), e.toString());
                    break;
                }
                acked.add(row.id());
            }
            if (acked.isEmpty()) {
                return 0;
            }
            // clock_timestamp(), not now(): now() is the transaction's start time, which would understate lag.
            List<Double> lags = jdbc.sql("""
                            UPDATE outbox_events SET published_at = clock_timestamp() WHERE id IN (:ids)
                            RETURNING EXTRACT(EPOCH FROM published_at - created_at)
                            """)
                    .param("ids", acked)
                    .query(Double.class).list();
            lags.forEach(lag -> metrics.outboxPublished(Duration.ofMillis(Math.round(lag * 1000))));
            return acked.size();
        });
        return published == null ? 0 : published;
    }

    private CompletableFuture<?> send(OutboxRow row) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(properties.topic(), row.aggregateId().toString(), row.payload());
        record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            return kafka.send(record);
        } catch (Exception e) {
            // send() itself can throw, e.g. when metadata isn't available within max.block.ms.
            return CompletableFuture.failedFuture(e);
        }
    }

    private record OutboxRow(UUID id, UUID aggregateId, String eventType, String payload) {
    }
}
