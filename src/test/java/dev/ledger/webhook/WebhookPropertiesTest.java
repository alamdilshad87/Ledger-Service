package dev.ledger.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class WebhookPropertiesTest {

    private final WebhookProperties defaults = new WebhookProperties(
            15, Duration.ofSeconds(1), Duration.ofHours(1), 20, Duration.ofSeconds(5), Duration.ofSeconds(30));

    @Test
    void backoffDoublesFromOneSecond() {
        assertThat(defaults.backoffAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.backoffAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.backoffAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(defaults.backoffAfter(12)).isEqualTo(Duration.ofSeconds(2048));
    }

    @Test
    void backoffIsCappedAtOneHour() {
        assertThat(defaults.backoffAfter(13)).isEqualTo(Duration.ofHours(1));
        assertThat(defaults.backoffAfter(1_000)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void allRetriesTakeAboutThreeHoursBeforeDeadLettering() {
        Duration total = Duration.ZERO;
        for (int failures = 1; failures < defaults.maxAttempts(); failures++) {
            total = total.plus(defaults.backoffAfter(failures));
        }
        assertThat(total).isBetween(Duration.ofHours(3), Duration.ofHours(3).plusMinutes(10));
    }
}
