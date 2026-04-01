package dev.ledger;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Shared setup and helpers for API tests running against a real Postgres. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Concurrency tests open 200 connections in the same instant. Tomcat's default accept backlog
        // is 100, and Windows refuses (rather than queues) connections beyond it.
        properties = {
                "server.tomcat.accept-count=1000",
                // Webhook retries in milliseconds instead of seconds-to-hours, so retry tests finish quickly.
                "ledger.webhooks.base-delay=PT0.05S",
                "ledger.webhooks.max-delay=PT0.4S",
                "ledger.webhooks.poll-interval=PT0.1S",
                "ledger.webhooks.max-attempts=25",
                // Tests run reconciliation by hand; "-" disables the nightly cron so it can't fire mid-test.
                "ledger.reconciliation.cron=-",
                "ledger.reconciliation.statement-dir=target/test-bank-statements"})
@Import(TestcontainersConfig.class)
public abstract class ApiTestSupport {

    protected static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected JdbcClient jdbc;

    /** POST with a fresh random idempotency key. */
    protected ResponseEntity<Map<String, Object>> post(String path, Object body) {
        return post(path, body, UUID.randomUUID().toString());
    }

    /** POST with the given idempotency key; {@code null} sends no key header at all. */
    protected ResponseEntity<Map<String, Object>> post(String path, Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JSON);
    }

    /** POST a raw JSON string exactly as written (to test whitespace and key order). */
    protected ResponseEntity<Map<String, Object>> postRaw(String path, String json, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), JSON);
    }

    protected ResponseEntity<Map<String, Object>> get(String path) {
        return http.exchange(path, HttpMethod.GET, null, JSON);
    }

    protected long newWallet() {
        var created = post("/v1/users", Map.of("email", "user-" + UUID.randomUUID() + "@example.com"));
        assertStatus(created, HttpStatus.CREATED);
        return asLong(created.getBody().get("walletAccountId"));
    }

    protected long newWalletWith(long paise) {
        long wallet = newWallet();
        assertStatus(post("/v1/topups", Map.of("accountId", wallet, "amount", paise)), HttpStatus.CREATED);
        return wallet;
    }

    /** Creates a merchant and returns {merchantId, accountId}. */
    protected Merchant newMerchant() {
        var created = post("/v1/merchants", Map.of("name", "Merchant " + UUID.randomUUID().toString().substring(0, 8)));
        assertStatus(created, HttpStatus.CREATED);
        return new Merchant(UUID.fromString((String) created.getBody().get("merchantId")),
                asLong(created.getBody().get("accountId")));
    }

    protected record Merchant(UUID id, long accountId) {
    }

    /** Pays a merchant and returns the payment id. */
    protected UUID pay(long wallet, Merchant merchant, long paise) {
        var paid = post("/v1/payments", Map.of("fromAccountId", wallet, "merchantId", merchant.id(), "amount", paise));
        assertStatus(paid, HttpStatus.CREATED);
        return UUID.fromString((String) paid.getBody().get("paymentId"));
    }

    protected long balanceOf(long accountId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = :id").param("id", accountId).query(Long.class).single();
    }

    protected long clearingAccountId() {
        return jdbc.sql("SELECT id FROM accounts WHERE type = 'SYSTEM_CLEARING'").query(Long.class).single();
    }

    protected static long asLong(Object number) {
        return ((Number) number).longValue();
    }

    protected static void assertStatus(ResponseEntity<?> response, HttpStatus expected) {
        if (response.getStatusCode() != expected) {
            throw new AssertionError("Expected " + expected + " but got " + response.getStatusCode() + ": " + response.getBody());
        }
    }
}
