package dev.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class UserAndAccountApiTest {

    @Autowired
    TestRestTemplate http;

    @SuppressWarnings("rawtypes")
    @Test
    void createUserGivesAnEmptyInrWallet() {
        String email = "asha-" + UUID.randomUUID() + "@example.com";

        ResponseEntity<Map> created = http.postForEntity("/v1/users", Map.of("email", email), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number walletId = (Number) created.getBody().get("walletAccountId");

        ResponseEntity<Map> account = http.getForEntity("/v1/accounts/" + walletId, Map.class);
        assertThat(account.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(account.getBody())
                .containsEntry("type", "USER_WALLET")
                .containsEntry("currency", "INR")
                .containsEntry("balance", 0)
                .containsEntry("ownerId", created.getBody().get("userId"));
    }

    @SuppressWarnings("rawtypes")
    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        String email = "ravi-" + UUID.randomUUID() + "@example.com";
        http.postForEntity("/v1/users", Map.of("email", email), Map.class);

        ResponseEntity<Map> again = http.postForEntity("/v1/users", Map.of("email", email.toUpperCase()), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "EMAIL_EXISTS").containsKey("traceId");
    }

    @SuppressWarnings("rawtypes")
    @Test
    void invalidEmailIs400() {
        ResponseEntity<Map> response = http.postForEntity("/v1/users", Map.of("email", "not-an-email"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_FAILED");
    }

    @SuppressWarnings("rawtypes")
    @Test
    void unknownAccountIs404() {
        ResponseEntity<Map> response = http.getForEntity("/v1/accounts/999999999", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "ACCOUNT_NOT_FOUND");
    }
}
