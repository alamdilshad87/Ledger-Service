package dev.ledger.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes idempotency keys older than their TTL (24 h by default) once an hour.
 * Redis entries expire on their own with the same TTL. So a key is forgotten 24–25 h after first use.
 */
@Component
public class IdempotencyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;

    public IdempotencyCleanup(IdempotencyStore store, IdempotencyProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ledger.idempotency.cleanup-interval:PT1H}",
            initialDelayString = "${ledger.idempotency.cleanup-interval:PT1H}")
    public int purgeExpired() {
        int deleted = store.deleteOlderThan(properties.ttl());
        if (deleted > 0) {
            log.info("Deleted {} idempotency keys older than {}", deleted, properties.ttl());
        }
        return deleted;
    }
}
