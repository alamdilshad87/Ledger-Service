package dev.ledger.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MerchantApiTest extends ApiTestSupport {

    @Test
    void createMerchantGivesAnEmptyMerchantAccount() {
        var created = post("/v1/merchants", Map.of("name", "  Chai Point  "));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("name", "Chai Point").containsKey("merchantId");
        long accountId = asLong(created.getBody().get("accountId"));
        var account = get("/v1/accounts/" + accountId).getBody();
        assertThat(account).containsEntry("type", "MERCHANT").containsEntry("balance", 0).containsEntry("currency", "INR");
    }

    @Test
    void blankOrLongNameIs400() {
        assertThat(post("/v1/merchants", Map.of("name", " ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/v1/merchants", Map.of("name", "x".repeat(101))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void merchantAccountCannotBeToppedUpOrTransferredFrom() {
        long merchantAccount = asLong(post("/v1/merchants", Map.of("name", "Shop")).getBody().get("accountId"));

        assertThat(post("/v1/topups", Map.of("accountId", merchantAccount, "amount", 100)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post("/v1/transfers", Map.of("fromAccountId", merchantAccount, "toAccountId", newWallet(), "amount", 100))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
