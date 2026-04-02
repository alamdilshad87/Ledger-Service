package dev.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class OpenApiTest extends ApiTestSupport {

    private String getWithoutKey(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "");
        var response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    void apiDocsArePublicAndDescribeAuthAndIdempotency() {
        String spec = getWithoutKey("/v3/api-docs");

        assertThat(spec)
                .contains("\"title\":\"Ledger Service API\"")
                .contains("\"/v1/transfers\"", "\"/v1/payments/{paymentId}/refunds\"", "\"/v1/admin/api-keys\"")
                .contains("\"scheme\":\"bearer\"")
                .contains("\"name\":\"Idempotency-Key\"");
    }

    @Test
    void swaggerUiLoads() {
        assertThat(getWithoutKey("/swagger-ui/index.html")).contains("Swagger UI");
    }
}
