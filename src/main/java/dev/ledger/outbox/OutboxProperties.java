package dev.ledger.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledger.outbox")
public record OutboxProperties(
        @DefaultValue("ledger-events") String topic,
        @DefaultValue("100") int batchSize,
        @DefaultValue("5s") Duration sendTimeout) {
}
