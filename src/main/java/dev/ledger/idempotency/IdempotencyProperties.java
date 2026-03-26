package dev.ledger.idempotency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param ttl   how long a key is remembered; a reused key after this is a new request
 * @param lease how long an IN_PROGRESS claim is honoured before another request with the same key and
 *              body may take it over (e.g. after the original server crashed mid-request)
 */
@ConfigurationProperties("ledger.idempotency")
public record IdempotencyProperties(
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("60s") Duration lease) {
}
