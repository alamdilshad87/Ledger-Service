package dev.ledger.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConsumerConfig {

    /**
     * Retry a failing record (e.g. Postgres briefly down) with backoff up to 30 s, forever. The default
     * handler gives up after 10 quick tries and skips the record, which here would silently drop a webhook.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(30_000);
        return new DefaultErrorHandler(backOff);
    }
}
