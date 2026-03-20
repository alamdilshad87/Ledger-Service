package dev.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TransferApiTest extends ApiTestSupport {

    private static Map<String, Object> transfer(long from, long to, long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("fromAccountId", from);
        body.put("toAccountId", to);
        body.put("amount", amount);
        return body;
    }

    @Test
    void transferMovesMoneyBetweenWallets() {
        long asha = newWalletWith(120_000);
        long ravi = newWallet();

        Map<String, Object> body = transfer(asha, ravi, 50_000);
        body.put("note", "Dinner split");
        var response = post("/v1/transfers", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED").containsKeys("entryId", "createdAt");
        assertThat(asLong(response.getBody().get("fromBalance"))).isEqualTo(70_000);
        assertThat(balanceOf(asha)).isEqualTo(70_000);
        assertThat(balanceOf(ravi)).isEqualTo(50_000);
    }

    @Test
    void transferOfWholeBalanceLeavesZero() {
        long asha = newWalletWith(10_000);
        long ravi = newWallet();

        assertThat(post("/v1/transfers", transfer(asha, ravi, 10_000)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balanceOf(asha)).isZero();
    }

    @Test
    void insufficientFundsIs402AndNothingMoves() {
        long asha = newWalletWith(10_000);
        long ravi = newWallet();
        long entriesBefore = jdbc.sql("SELECT COUNT(*) FROM journal_entries").query(Long.class).single();

        var response = post("/v1/transfers", transfer(asha, ravi, 10_001));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody()).containsEntry("code", "INSUFFICIENT_FUNDS");
        assertThat(balanceOf(asha)).isEqualTo(10_000);
        assertThat(balanceOf(ravi)).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM journal_entries").query(Long.class).single())
                .as("no partial entry left behind").isEqualTo(entriesBefore);
    }

    @Test
    void transferToSelfIs422() {
        long asha = newWalletWith(10_000);

        var response = post("/v1/transfers", transfer(asha, asha, 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "SAME_ACCOUNT");
    }

    @Test
    void transferFromClearingAccountIs422() {
        long ravi = newWallet();

        var response = post("/v1/transfers", transfer(clearingAccountId(), ravi, 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "NOT_A_WALLET");
        assertThat(balanceOf(ravi)).isZero();
    }

    @Test
    void unknownRecipientIs404() {
        long asha = newWalletWith(10_000);

        var response = post("/v1/transfers", transfer(asha, 999_999_999, 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(balanceOf(asha)).isEqualTo(10_000);
    }

    @Test
    void missingIdempotencyKeyIs400() {
        long asha = newWalletWith(10_000);
        long ravi = newWallet();

        var response = post("/v1/transfers", transfer(asha, ravi, 100), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void noteLongerThan140CharsIs400() {
        long asha = newWalletWith(10_000);
        long ravi = newWallet();
        Map<String, Object> body = transfer(asha, ravi, 100);
        body.put("note", "x".repeat(141));

        assertThat(post("/v1/transfers", body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
