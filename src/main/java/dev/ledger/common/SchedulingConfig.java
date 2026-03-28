package dev.ledger.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs: idempotency key cleanup now; outbox relay and reconciliation later. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
