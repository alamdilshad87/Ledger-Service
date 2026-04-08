package dev.ledger.common;

import java.time.Duration;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Business metrics, alongside Spring's built-in HTTP, JVM and connection-pool metrics.
 * Prometheus names add {@code _total} to counters and {@code _seconds} to timers.
 */
@Component
public class LedgerMetrics {

    private final MeterRegistry registry;

    public LedgerMetrics(MeterRegistry registry, JdbcClient jdbc) {
        this.registry = registry;

        // Backlogs, read from Postgres on each scrape. Cheap: both use partial indexes on the pending rows.
        gauge("ledger.outbox.pending", "Outbox events not yet published to Kafka",
                () -> jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single());
        gauge("ledger.outbox.oldest.pending.age.seconds", "Age of the oldest unpublished outbox event",
                () -> jdbc.sql("SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - MIN(created_at)), 0) "
                        + "FROM outbox_events WHERE published_at IS NULL").query(Double.class).single());
        gauge("ledger.webhooks.pending", "Webhook deliveries waiting to be sent or retried",
                () -> jdbc.sql("SELECT COUNT(*) FROM webhook_deliveries WHERE status = 'PENDING'").query(Long.class).single());
        // Postgres's own count of deadlocks it had to break in this database (cumulative since stats reset).
        // Load tests read it before and after a run: the difference must be 0.
        gauge("ledger.db.deadlocks", "Deadlocks Postgres detected in this database",
                () -> jdbc.sql("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")
                        .query(Long.class).single());
        gauge("ledger.reconciliation.last.mismatches", "Mismatches found by the most recent reconciliation run",
                () -> jdbc.sql("SELECT COALESCE((SELECT mismatch_count FROM reconciliation_runs ORDER BY ran_at DESC LIMIT 1), 0)")
                        .query(Long.class).single());
    }

    /** A journal entry committed (counted per entry type), and the paise it moved. */
    public void entryPosted(String type, long amountMoved) {
        Counter.builder("ledger.entries.posted").description("Journal entries posted").tag("type", type)
                .register(registry).increment();
        Counter.builder("ledger.money.moved.paise").description("Paise moved, by entry type").baseUnit("paise")
                .tag("type", type).register(registry).increment(amountMoved);
    }

    /** A money movement refused because a wallet would go negative. */
    public void insufficientFunds() {
        Counter.builder("ledger.entries.declined").description("Entries declined").tag("reason", "insufficient_funds")
                .register(registry).increment();
    }

    public void outboxPublished(Duration lag) {
        Timer.builder("ledger.outbox.publish.lag").description("Time from commit to Kafka ack")
                .register(registry).record(lag);
    }

    /** outcome: delivered, failed (will retry) or dead (dead-lettered). */
    public void webhookAttempt(String outcome) {
        Counter.builder("ledger.webhooks.attempts").description("Webhook delivery attempts").tag("outcome", outcome)
                .register(registry).increment();
    }

    /** source: redis or postgres. */
    public void idempotentReplay(String source) {
        Counter.builder("ledger.idempotency.replays").description("Retries answered with a stored response")
                .tag("source", source).register(registry).increment();
    }

    /** type: in_progress (409) or key_reused (422). */
    public void idempotencyConflict(String type) {
        Counter.builder("ledger.idempotency.conflicts").description("Idempotency conflicts").tag("type", type)
                .register(registry).increment();
    }

    public void rateLimited(String client) {
        Counter.builder("ledger.ratelimit.rejected").description("Requests rejected with 429").tag("client", client)
                .register(registry).increment();
    }

    private void gauge(String name, String description, Supplier<Number> query) {
        Gauge.builder(name, () -> {
            try {
                return query.get();
            } catch (RuntimeException e) {
                return Double.NaN;   // database unavailable: report "unknown", don't fail the scrape
            }
        }).description(description).register(registry);
    }
}
