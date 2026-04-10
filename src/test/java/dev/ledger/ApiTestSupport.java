package dev.ledger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;
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
                "ledger.reconciliation.statement-dir=target/test-bank-statements",
                "ledger.auth.bootstrap-admin-key=" + ApiTestSupport.ADMIN_KEY})
@Import(TestcontainersConfig.class)
// Spring Boot tests switch metric export off by default; keep it on so /actuator/prometheus exists.
@AutoConfigureObservability(tracing = false)
public abstract class ApiTestSupport {

    /** Registered at startup by BootstrapAdminKey and sent on every test request unless one is already set. */
    public static final String ADMIN_KEY = "lk_test_admin_key_for_tests_only_000000000000";

    protected static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void authenticateTestRequests() {
        RestTemplate rest = http.getRestTemplate();
        if (rest.getInterceptors().stream().noneMatch(i -> i instanceof AdminKeyInterceptor)) {
            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(rest.getInterceptors());
            interceptors.add(new AdminKeyInterceptor());
            rest.setInterceptors(interceptors);
        }
    }

    private static final class AdminKeyInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(ADMIN_KEY);
            }
            return execution.execute(request, body);
        }
    }

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

    /**
     * POST the way a well-behaved client does under load: on 503 (busy) or 409 (an earlier attempt is still
     * running), wait briefly and retry with the <em>same</em> idempotency key, so the operation happens at most
     * once however many attempts it takes. Gives up after 90 s, longer than the 60 s idempotency lease.
     */
    protected ResponseEntity<Map<String, Object>> postRetryingWhileBusy(String path, Object body) {
        String key = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        while (true) {
            var response = post(path, body, key);
            int status = response.getStatusCode().value();
            if ((status != 503 && status != 409) || System.nanoTime() > deadline) {
                return response;
            }
            try {
                Thread.sleep(50 + (long) (Math.random() * 200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return response;
            }
        }
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

    /** How an Idempotency-Key sent with the test admin key is stored: "<api key id>:<key>". */
    protected String storedIdempotencyKey(String clientKey) {
        String adminId = jdbc.sql("SELECT id::text FROM api_keys WHERE name = 'bootstrap-admin'").query(String.class).single();
        return adminId + ":" + clientKey;
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
