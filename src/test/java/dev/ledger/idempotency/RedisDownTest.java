package dev.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Redis points at a port nothing listens on. Idempotency must still hold, just slower. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
                // No Kafka in this context: don't try to create topics or publish.
                "ledger.outbox.relay.enabled=false", "spring.kafka.admin.auto-create=false",
                "spring.kafka.listener.auto-startup=false"})
@Import(RedisDownTest.PostgresOnly.class)
class RedisDownTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresOnly {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        }
    }

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient jdbc;

    private ResponseEntity<Map<String, Object>> post(String path, Object body, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JSON);
    }

    @Test
    void duplicatesAreStillCaughtByPostgresWhenRedisIsUnreachable() {
        var user = post("/v1/users", Map.of("email", "down-" + UUID.randomUUID() + "@example.com"), "unused");
        long wallet = ((Number) user.getBody().get("walletAccountId")).longValue();
        String key = UUID.randomUUID().toString();
        var body = Map.of("accountId", wallet, "amount", 5_000);

        var first = post("/v1/topups", body, key);
        var retry = post("/v1/topups", body, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(jdbc.sql("SELECT balance FROM accounts WHERE id = :id").param("id", wallet)
                .query(Long.class).single()).isEqualTo(5_000);
    }
}
