package dev.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/**
 * A tiny pool (2 connections, 300 ms wait) that the test drains by holding every connection itself,
 * so each request finds no connection: the service must answer 503 quickly and leave nothing half-done.
 */
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=300",
        "ledger.auth.key-cache-ttl=0s",                 // force a DB lookup on every request
        "ledger.outbox.relay.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "ledger.webhooks.poll-interval=PT1H"})
class OverloadTest extends ApiTestSupport {

    @Autowired
    DataSource dataSource;

    private List<Connection> drainPool() throws Exception {
        List<Connection> held = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            held.add(dataSource.getConnection());
        }
        return held;
    }

    private static void release(List<Connection> held) throws Exception {
        for (Connection c : held) {
            c.close();
        }
    }

    @Test
    void requestsGet503QuicklyWhenNoConnectionIsAvailable() throws Exception {
        long wallet = newWallet();
        List<Connection> held = drainPool();
        try {
            long start = System.nanoTime();
            var read = get("/v1/accounts/" + wallet);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(read.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(read.getBody()).containsEntry("code", "SERVICE_BUSY");
            assertThat(read.getHeaders().getFirst("Retry-After")).isEqualTo("1");
            assertThat(elapsedMs).as("fails fast, not after 30 s").isLessThan(3_000);
        } finally {
            release(held);
        }
    }

    @Test
    void busyMoneyRequestMovesNothingAndCanBeRetriedWithTheSameKey() throws Exception {
        long wallet = newWallet();
        String key = UUID.randomUUID().toString();
        var body = Map.of("accountId", wallet, "amount", 700);

        List<Connection> held = drainPool();
        try {
            assertThat(post("/v1/topups", body, key).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            release(held);
        }

        var retry = post("/v1/topups", body, key);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(balanceOf(wallet)).isEqualTo(700);
    }
}
