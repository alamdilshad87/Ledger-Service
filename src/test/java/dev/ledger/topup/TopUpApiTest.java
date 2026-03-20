package dev.ledger.topup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TopUpApiTest extends ApiTestSupport {

    @Test
    void topUpCreditsWalletAndDebitsClearingAccount() {
        long wallet = newWallet();
        long clearingBefore = balanceOf(clearingAccountId());

        var response = post("/v1/topups", Map.of("accountId", wallet, "amount", 50_000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED").containsKey("entryId");
        assertThat(asLong(response.getBody().get("balance"))).isEqualTo(50_000);
        assertThat(balanceOf(wallet)).isEqualTo(50_000);
        assertThat(balanceOf(clearingAccountId())).isEqualTo(clearingBefore - 50_000);
    }

    @Test
    void topUpWritesOneBalancedEntryWithTwoPostings() {
        long wallet = newWallet();

        var response = post("/v1/topups", Map.of("accountId", wallet, "amount", 12_345), "my-key-1");

        String entryId = (String) response.getBody().get("entryId");
        var sum = jdbc.sql("SELECT COUNT(*) AS n, SUM(amount) AS total FROM postings WHERE entry_id = CAST(:id AS uuid)")
                .param("id", entryId)
                .query((rs, i) -> new long[] {rs.getLong("n"), rs.getLong("total")})
                .single();
        assertThat(sum).containsExactly(2, 0);
        assertThat(jdbc.sql("SELECT idempotency_key FROM journal_entries WHERE id = CAST(:id AS uuid)")
                .param("id", entryId).query(String.class).single()).isEqualTo("my-key-1");
    }

    @Test
    void missingIdempotencyKeyIs400() {
        long wallet = newWallet();

        var response = post("/v1/topups", Map.of("accountId", wallet, "amount", 100), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "MISSING_IDEMPOTENCY_KEY");
        assertThat(balanceOf(wallet)).isZero();
    }

    @Test
    void zeroOrNegativeAmountIs400() {
        long wallet = newWallet();

        assertThat(post("/v1/topups", Map.of("accountId", wallet, "amount", 0)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/v1/topups", Map.of("accountId", wallet, "amount", -500)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balanceOf(wallet)).isZero();
    }

    @Test
    void amountAboveLimitIs400() {
        long wallet = newWallet();

        var response = post("/v1/topups", Map.of("accountId", wallet, "amount", 1_000_000_001L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownAccountIs404() {
        var response = post("/v1/topups", Map.of("accountId", 999_999_999, "amount", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "ACCOUNT_NOT_FOUND");
    }

    @Test
    void clearingAccountCannotBeToppedUp() {
        var response = post("/v1/topups", Map.of("accountId", clearingAccountId(), "amount", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "NOT_A_WALLET");
    }
}
