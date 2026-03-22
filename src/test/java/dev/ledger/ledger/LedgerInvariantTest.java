package dev.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Week 2 "done when": 1,000 random transfers leave the ledger balanced.
 * Runs sequentially through the real HTTP API; concurrency is week 3's test.
 */
class LedgerInvariantTest extends ApiTestSupport {

    private static final int WALLETS = 20;
    private static final int TRANSFERS = 1_000;
    private static final long OPENING_BALANCE = 100_000; // ₹1,000 each

    @Test
    void thousandRandomTransfersKeepTheLedgerBalanced() {
        Random random = new Random(42); // fixed seed: a failure is reproducible
        List<Long> wallets = new ArrayList<>();
        for (int i = 0; i < WALLETS; i++) {
            wallets.add(newWalletWith(OPENING_BALANCE));
        }

        int completed = 0;
        int declined = 0;
        for (int i = 0; i < TRANSFERS; i++) {
            long from = wallets.get(random.nextInt(WALLETS));
            long to = wallets.get(random.nextInt(WALLETS));
            if (from == to) {
                continue;
            }
            long amount = 1 + random.nextInt(60_000); // up to ₹600, so some transfers bounce
            var response = post("/v1/transfers", Map.of("fromAccountId", from, "toAccountId", to, "amount", amount));
            if (response.getStatusCode() == HttpStatus.CREATED) {
                completed++;
            } else {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                declined++;
            }
        }

        // Both paths were exercised, otherwise the test proves less than it claims.
        assertThat(completed).isGreaterThan(500);
        assertThat(declined).isGreaterThan(0);

        // 1. Money never appears or disappears: every posting in the system sums to zero.
        long total = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single();
        assertThat(total).isZero();

        // 2. Every cached balance equals the sum of that account's postings.
        List<String> drifted = jdbc.sql("""
                        SELECT a.id, a.balance, COALESCE(SUM(p.amount), 0) AS from_postings
                        FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
                        GROUP BY a.id, a.balance
                        HAVING a.balance <> COALESCE(SUM(p.amount), 0)
                        """)
                .query((rs, n) -> "account " + rs.getLong("id") + ": cached " + rs.getLong("balance")
                        + " vs postings " + rs.getLong("from_postings"))
                .list();
        assertThat(drifted).isEmpty();

        // 3. Transfers only move money around inside this closed group of wallets.
        long groupTotal = wallets.stream().mapToLong(this::balanceOf).sum();
        assertThat(groupTotal).isEqualTo(WALLETS * OPENING_BALANCE);

        // 4. No wallet went negative.
        assertThat(wallets).allSatisfy(w -> assertThat(balanceOf(w)).isNotNegative());
    }
}
