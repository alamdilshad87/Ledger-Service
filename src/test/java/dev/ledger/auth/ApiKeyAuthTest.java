package dev.ledger.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiKeyAuthTest extends ApiTestSupport {

    /** Request with exactly this Authorization header ({@code null} = none), bypassing the test admin key. */
    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, Object body, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        // Present-but-empty header stops the test interceptor adding the admin key.
        headers.set(HttpHeaders.AUTHORIZATION, authorization == null ? "" : authorization);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JSON);
    }

    private String newKey(String role) {
        var created = post("/v1/admin/api-keys", Map.of("name", "test " + role.toLowerCase(), "role", role));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("key");
    }

    @Test
    void requestWithoutAKeyIs401() {
        var response = call(HttpMethod.GET, "/v1/accounts/1", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).startsWith("Bearer");
        assertThat(response.getBody()).containsEntry("code", "UNAUTHENTICATED");
    }

    @Test
    void unknownKeyIs401() {
        var response = call(HttpMethod.GET, "/v1/accounts/1", null, "Bearer lk_" + "0".repeat(64));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "INVALID_API_KEY");
    }

    @Test
    void clientKeyCanMoveMoneyButNotUseAdminEndpoints() {
        String client = newKey("CLIENT");

        var user = call(HttpMethod.POST, "/v1/users", Map.of("email", "c-" + UUID.randomUUID() + "@example.com"),
                "Bearer " + client);
        var admin = call(HttpMethod.GET, "/v1/admin/reconciliation/latest", null, "Bearer " + client);

        assertThat(user.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(admin.getBody()).containsEntry("code", "FORBIDDEN");
    }

    @Test
    void newKeyIsShownOnceAndOnlyItsHashIsStored() {
        var created = post("/v1/admin/api-keys", Map.of("name", "partner-app", "role", "CLIENT"));
        String key = (String) created.getBody().get("key");

        assertThat(key).matches("lk_[0-9a-f]{64}");
        assertThat(created.getBody()).containsEntry("prefix", key.substring(0, 11));
        long plainCopies = jdbc.sql("SELECT COUNT(*) FROM api_keys WHERE key_hash = :k OR key_prefix = :k")
                .param("k", key).query(Long.class).single();
        assertThat(plainCopies).isZero();
    }

    @Test
    void revokedKeyStopsWorking() {
        var created = post("/v1/admin/api-keys", Map.of("name", "to-revoke", "role", "CLIENT"));
        String key = (String) created.getBody().get("key");
        assertThat(call(HttpMethod.GET, "/v1/accounts/1", null, "Bearer " + key).getStatusCode()).isEqualTo(HttpStatus.OK);

        http.delete("/v1/admin/api-keys/" + created.getBody().get("id"));

        assertThat(call(HttpMethod.GET, "/v1/accounts/1", null, "Bearer " + key).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthCheckNeedsNoKey() {
        assertThat(call(HttpMethod.GET, "/actuator/health", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
