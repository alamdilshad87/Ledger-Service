package dev.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UserAndAccountApiTest extends ApiTestSupport {

    @Test
    void createUserGivesAnEmptyInrWallet() {
        String email = "asha-" + UUID.randomUUID() + "@example.com";

        var created = post("/v1/users", Map.of("email", email));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long walletId = asLong(created.getBody().get("walletAccountId"));

        var account = get("/v1/accounts/" + walletId);
        assertThat(account.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(account.getBody())
                .containsEntry("type", "USER_WALLET")
                .containsEntry("currency", "INR")
                .containsEntry("balance", 0)
                .containsEntry("ownerId", created.getBody().get("userId"));
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        String email = "ravi-" + UUID.randomUUID() + "@example.com";
        post("/v1/users", Map.of("email", email));

        var again = post("/v1/users", Map.of("email", email.toUpperCase()));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "EMAIL_EXISTS").containsKey("traceId");
    }

    @Test
    void invalidEmailIs400() {
        var response = post("/v1/users", Map.of("email", "not-an-email"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void unknownAccountIs404() {
        var response = get("/v1/accounts/999999999");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "ACCOUNT_NOT_FOUND");
    }
}
