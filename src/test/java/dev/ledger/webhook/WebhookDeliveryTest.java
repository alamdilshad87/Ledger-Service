package dev.ledger.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import dev.ledger.ApiTestSupport;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

class WebhookDeliveryTest extends ApiTestSupport {

    @Autowired
    KafkaTemplate<String, String> kafka;

    private String subscribe(Merchant merchant, MockMerchantServer server) {
        var created = post("/v1/webhooks", Map.of("merchantId", merchant.id(), "url", server.url()));
        String secret = (String) created.getBody().get("secret");
        server.useSecret(secret);
        return (String) created.getBody().get("subscriptionId");
    }

    private List<String> merchantEventIds(Merchant merchant) {
        return jdbc.sql("SELECT id::text FROM outbox_events WHERE payload -> 'data' ->> 'merchantId' = :m ORDER BY seq")
                .param("m", merchant.id().toString()).query(String.class).list();
    }

    private long count(String sql, String subscriptionId) {
        return jdbc.sql(sql).param("s", subscriptionId).query(Long.class).single();
    }

    private static void await(BooleanSupplier condition, Duration timeout, String what) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    /** Week 6 "done when": a merchant failing half of all requests still gets every event exactly once. */
    @Test
    void merchantFailingHalfTheTimeStillGetsEveryEventExactlyOnce() throws Exception {
        try (MockMerchantServer server = new MockMerchantServer(0.5, 42)) {
            Merchant shop = newMerchant();
            String subscription = subscribe(shop, server);
            long asha = newWalletWith(1_000_000);

            for (int i = 0; i < 20; i++) {
                UUID payment = pay(asha, shop, 1_000 + i);
                if (i % 4 == 0) {
                    post("/v1/payments/" + payment + "/refunds", Map.of("amount", 500));
                }
            }
            List<String> expected = merchantEventIds(shop);
            assertThat(expected).hasSize(25);   // 20 payments + 5 refunds

            await(() -> count("SELECT COUNT(*) FROM webhook_deliveries WHERE subscription_id = CAST(:s AS uuid) AND status = 'DELIVERED'",
                    subscription) == 25, Duration.ofSeconds(60), "all 25 deliveries");

            System.out.printf("Flaky merchant: %d requests, %d answered 500, %d events accepted, max attempts for one event %d%n",
                    server.requests.get(), server.failuresReturned.get(), server.processed.size(),
                    jdbc.sql("SELECT MAX(attempts) FROM webhook_deliveries WHERE subscription_id = CAST(:s AS uuid)")
                            .param("s", subscription).query(Integer.class).single());
            assertThat(server.failuresReturned.get()).as("the merchant really did fail requests").isGreaterThan(10);
            assertThat(server.badSignatures.get()).isZero();
            assertThat(server.processed.keySet()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(server.accepted.values()).allSatisfy(n -> assertThat(n.get()).as("accepted once").isEqualTo(1));
            assertThat(count("SELECT COUNT(*) FROM webhook_dead_letters WHERE subscription_id = CAST(:s AS uuid)", subscription))
                    .isZero();
        }
    }

    @Test
    void eventDeliveredTwiceByKafkaIsSentToTheMerchantOnce() throws Exception {
        try (MockMerchantServer server = new MockMerchantServer(0.0, 1)) {
            Merchant shop = newMerchant();
            String subscription = subscribe(shop, server);
            UUID payment = pay(newWalletWith(10_000), shop, 2_000);
            await(() -> server.processed.size() == 1, Duration.ofSeconds(20), "first delivery");

            // Simulate the outbox relay's at-least-once redelivery: publish the exact same event again.
            String payload = jdbc.sql("SELECT payload::text FROM outbox_events WHERE aggregate_id = :id")
                    .param("id", payment).query(String.class).single();
            kafka.send(new ProducerRecord<>("ledger-events", payment.toString(), payload)).get();
            Thread.sleep(3_000);   // give the consumer and worker time to (wrongly) send it again

            assertThat(server.requests.get()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM webhook_deliveries WHERE subscription_id = CAST(:s AS uuid)", subscription))
                    .isEqualTo(1);
        }
    }

    @Test
    void deliveryThatKeepsFailingIsDeadLettered() throws Exception {
        try (MockMerchantServer server = new MockMerchantServer(1.0, 7)) {
            Merchant shop = newMerchant();
            String subscription = subscribe(shop, server);
            pay(newWalletWith(10_000), shop, 3_000);
            await(() -> server.requests.get() >= 3, Duration.ofSeconds(20), "a few failed attempts");

            // Skip ahead: pretend it has already failed 24 of its 25 attempts.
            jdbc.sql("UPDATE webhook_deliveries SET attempts = 24 WHERE subscription_id = CAST(:s AS uuid)")
                    .param("s", subscription).update();
            await(() -> count("SELECT COUNT(*) FROM webhook_dead_letters WHERE subscription_id = CAST(:s AS uuid)",
                    subscription) == 1, Duration.ofSeconds(20), "dead letter");

            var deadLetter = jdbc.sql("SELECT attempts, last_status_code, last_error FROM webhook_dead_letters "
                            + "WHERE subscription_id = CAST(:s AS uuid)")
                    .param("s", subscription)
                    .query((rs, n) -> Map.of("attempts", rs.getInt(1), "status", rs.getInt(2), "error", rs.getString(3)))
                    .single();
            assertThat(deadLetter).containsEntry("attempts", 25).containsEntry("status", 500).containsEntry("error", "HTTP 500");
            assertThat(count("SELECT COUNT(*) FROM webhook_deliveries WHERE subscription_id = CAST(:s AS uuid) AND status = 'DEAD'",
                    subscription)).isEqualTo(1);

            int requestsWhenDead = server.requests.get();
            Thread.sleep(1_000);
            assertThat(server.requests.get()).as("no more attempts after dead-lettering").isEqualTo(requestsWhenDead);
        }
    }

    @Test
    void retriesBackOffExponentially() throws Exception {
        try (MockMerchantServer server = new MockMerchantServer(1.0, 3)) {
            Merchant shop = newMerchant();
            String subscription = subscribe(shop, server);
            pay(newWalletWith(10_000), shop, 1_000);

            // Test delays: 50, 100, 200, 400, 400 … ms. Six attempts need at least ~0.75 s of waiting.
            long start = System.nanoTime();
            await(() -> server.requests.get() >= 6, Duration.ofSeconds(20), "six attempts");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isGreaterThanOrEqualTo(750);
            int attempts = jdbc.sql("SELECT attempts FROM webhook_deliveries WHERE subscription_id = CAST(:s AS uuid)")
                    .param("s", subscription).query(Integer.class).single();
            assertThat(attempts).isGreaterThanOrEqualTo(6);
        }
    }
}
