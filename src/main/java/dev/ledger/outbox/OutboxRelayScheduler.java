package dev.ledger.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the relay every 500 ms, draining several batches in one tick when there's a backlog.
 * Turn off with {@code ledger.outbox.relay.enabled=false} (used by the crash-recovery test and drill).
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final int MAX_BATCHES_PER_TICK = 10;

    private final OutboxRelay relay;
    private final OutboxProperties properties;

    public OutboxRelayScheduler(OutboxRelay relay, OutboxProperties properties) {
        this.relay = relay;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.relay.interval:PT0.5S}")
    public void tick() {
        for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
            if (relay.publishBatch() < properties.batchSize()) {
                return;   // caught up, or a send failed: wait for the next tick
            }
        }
    }
}
