package dev.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    private final RequestFingerprint fingerprint = new RequestFingerprint();

    private String hash(String method, String path, String body) {
        return fingerprint.of(method, path, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void keyOrderAndWhitespaceDoNotMatter() {
        assertThat(hash("POST", "/v1/transfers", "{\"fromAccountId\":2,\"toAccountId\":3,\"amount\":500}"))
                .isEqualTo(hash("POST", "/v1/transfers", "{ \"amount\": 500,\n  \"toAccountId\": 3, \"fromAccountId\": 2 }"));
    }

    @Test
    void nestedObjectsAreCanonicalisedToo() {
        assertThat(hash("POST", "/v1/x", "{\"a\":{\"y\":1,\"x\":2}}"))
                .isEqualTo(hash("POST", "/v1/x", "{\"a\":{\"x\":2,\"y\":1}}"));
    }

    @Test
    void differentValuesGiveDifferentHashes() {
        assertThat(hash("POST", "/v1/transfers", "{\"amount\":500}"))
                .isNotEqualTo(hash("POST", "/v1/transfers", "{\"amount\":501}"));
    }

    @Test
    void sameBodyOnDifferentEndpointIsADifferentRequest() {
        String body = "{\"accountId\":2,\"amount\":500}";
        assertThat(hash("POST", "/v1/topups", body)).isNotEqualTo(hash("POST", "/v1/transfers", body));
    }

    @Test
    void invalidJsonIsHashedAsRawBytes() {
        assertThat(hash("POST", "/v1/x", "{not json")).hasSize(64)
                .isNotEqualTo(hash("POST", "/v1/x", "{not  json"));
    }
}
