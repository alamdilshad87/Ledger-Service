package dev.ledger.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.ledger.common.LedgerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends pending webhooks and retries failures with exponential backoff.
 * <p>
 * Claiming a batch is a short transaction that pushes each row's {@code next_attempt_at} forward by a
 * lease ({@code FOR UPDATE SKIP LOCKED}, so parallel workers take different rows). The HTTP calls then
 * happen <em>outside</em> any transaction, so a slow merchant never holds a database connection. If the
 * app dies mid-send, the lease runs out and the row is picked up again: at-least-once, like the outbox.
 * Merchants dedupe on the {@code Ledger-Event-Id} header.
 */
@Component
public class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);
    private static final int MAX_BATCHES_PER_TICK = 10;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final WebhookProperties properties;
    private final LedgerMetrics metrics;
    private final HttpClient http;

    public WebhookDeliveryWorker(JdbcClient jdbc, TransactionTemplate transaction, WebhookProperties properties,
                                 LedgerMetrics metrics) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.properties = properties;
        this.metrics = metrics;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)   // a redirect could point a signed payload anywhere
                .build();
    }

    @Scheduled(fixedDelayString = "${ledger.webhooks.poll-interval:PT1S}")
    public void tick() {
        for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
            if (deliverBatch() < properties.batchSize()) {
                return;
            }
        }
    }

    /** Claims and sends one batch of due deliveries; returns how many were attempted. */
    public int deliverBatch() {
        List<Due> due = claimDue();
        due.forEach(this::attempt);
        return due.size();
    }

    private List<Due> claimDue() {
        return jdbc.sql("""
                        UPDATE webhook_deliveries d
                        SET next_attempt_at = clock_timestamp() + make_interval(secs => CAST(:lease AS double precision))
                        FROM webhook_subscriptions s
                        WHERE s.id = d.subscription_id
                          AND d.id IN (SELECT id FROM webhook_deliveries
                                       WHERE status = 'PENDING' AND next_attempt_at <= clock_timestamp()
                                       ORDER BY next_attempt_at
                                       LIMIT :limit
                                       FOR UPDATE SKIP LOCKED)
                        RETURNING d.id, d.event_id, d.event_type, d.payload, s.url, s.secret
                        """)
                .param("lease", properties.lease().toMillis() / 1000.0)
                .param("limit", properties.batchSize())
                .query((rs, n) -> new Due(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("url"),
                        rs.getString("secret")))
                .list();
    }

    private void attempt(Due delivery) {
        long now = Instant.now().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.url()))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", "ledger-service-webhooks/1")
                .header("Ledger-Event-Id", delivery.eventId().toString())
                .header("Ledger-Event-Type", delivery.eventType())
                .header(WebhookSignature.HEADER, WebhookSignature.header(delivery.secret(), now, delivery.payload()))
                .POST(HttpRequest.BodyPublishers.ofString(delivery.payload()))
                .build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                markDelivered(delivery.id(), status);
            } else {
                recordFailure(delivery, status, "HTTP " + status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(delivery, null, "interrupted");
        } catch (Exception e) {
            recordFailure(delivery, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void markDelivered(UUID id, int status) {
        jdbc.sql("""
                        UPDATE webhook_deliveries
                        SET status = 'DELIVERED', attempts = attempts + 1, last_status_code = :status,
                            last_error = NULL, delivered_at = clock_timestamp()
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id).param("status", status).update();
        metrics.webhookAttempt("delivered");
    }

    private void recordFailure(Due delivery, Integer status, String error) {
        transaction.executeWithoutResult(tx -> {
            Integer attempts = jdbc.sql("""
                            UPDATE webhook_deliveries
                            SET attempts = attempts + 1, last_status_code = :status, last_error = :error
                            WHERE id = :id AND status = 'PENDING'
                            RETURNING attempts
                            """)
                    .param("id", delivery.id()).param("status", status).param("error", truncate(error))
                    .query(Integer.class).optional().orElse(null);
            if (attempts == null) {
                return;   // already delivered or dead-lettered by another worker
            }
            if (attempts >= properties.maxAttempts()) {
                deadLetter(delivery.id());
                metrics.webhookAttempt("dead");
                log.warn("Webhook {} for event {} dead-lettered after {} attempts: {}", delivery.id(), delivery.eventId(),
                        attempts, error);
            } else {
                metrics.webhookAttempt("failed");
                Duration wait = properties.backoffAfter(attempts);
                jdbc.sql("""
                                UPDATE webhook_deliveries
                                SET next_attempt_at = clock_timestamp() + make_interval(secs => CAST(:wait AS double precision))
                                WHERE id = :id
                                """)
                        .param("id", delivery.id()).param("wait", wait.toMillis() / 1000.0).update();
            }
        });
    }

    private void deadLetter(UUID id) {
        jdbc.sql("UPDATE webhook_deliveries SET status = 'DEAD' WHERE id = :id").param("id", id).update();
        jdbc.sql("""
                        INSERT INTO webhook_dead_letters
                            (delivery_id, event_id, subscription_id, payload, attempts, last_status_code, last_error)
                        SELECT id, event_id, subscription_id, payload, attempts, last_status_code, last_error
                        FROM webhook_deliveries WHERE id = :id
                        """)
                .param("id", id).update();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    private record Due(UUID id, UUID eventId, String eventType, String payload, String url, String secret) {
    }
}
