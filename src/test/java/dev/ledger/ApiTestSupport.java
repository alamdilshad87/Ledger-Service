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
        properties = "server.tomcat.accept-count=1000")
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
