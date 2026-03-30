package dev.ledger.webhook;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxAttempts    attempts before a delivery is dead-lettered
 * @param baseDelay      wait after the first failure; doubles after each further failure
 * @param maxDelay       cap on the wait between attempts
 * @param batchSize      deliveries claimed per poll
 * @param requestTimeout how long a merchant gets to answer
 * @param lease          how long a claimed delivery is hidden from other workers while being sent
 */
@ConfigurationProperties("ledger.webhooks")
public record WebhookProperties(
        @DefaultValue("15") int maxAttempts,
        @DefaultValue("1s") Duration baseDelay,
        @DefaultValue("1h") Duration maxDelay,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue("30s") Duration lease) {

    /** 1 s, 2 s, 4 s, 8 s … capped at maxDelay. {@code failures} counts failed attempts so far (≥ 1). */
    public Duration backoffAfter(int failures) {
        int exponent = Math.min(failures - 1, 30);   // 2^30 s is far past any sane cap; avoids overflow
        Duration delay = baseDelay.multipliedBy(1L << exponent);
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
