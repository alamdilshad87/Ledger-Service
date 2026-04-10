package dev.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Hot house accounts: one merchant and the clearing account take part in most requests at once, while the
 * same wallets also transfer between themselves. House accounts are no longer row-locked up front (see
 * LedgerService), so this checks the new lock order: no deadlocks, no lost updates, and every balance
 * still equals its postings.
 */
class HotAccountConcurrencyTest extends ApiTestSupport {

    private static final int WALLETS = 20;
    private static final int REQUESTS = 200;

    @RepeatedTest(5)
    void paymentsTopUpsAndTransfersOnHotAccountsStayConsistent() throws Exception {
        List<Long> wallets = new ArrayList<>();
        for (int i = 0; i < WALLETS; i++) {
            wallets.add(newWalletWith(100_000));
        }
        Merchant shop = newMerchant();
        long clearingBefore = balanceOf(clearingAccountId());

        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                go.await();
                long wallet = wallets.get(n % WALLETS);
                long other = wallets.get((n + 7) % WALLETS);
                var response = switch (n % 3) {
                    case 0 -> postRetryingWhileBusy("/v1/payments", Map.of("fromAccountId", wallet, "merchantId", shop.id(), "amount", 1_000));
                    case 1 -> postRetryingWhileBusy("/v1/topups", Map.of("accountId", wallet, "amount", 500));
                    default -> postRetryingWhileBusy("/v1/transfers", Map.of("fromAccountId", wallet, "toAccountId", other, "amount", 300));
                };
                outcomes.computeIfAbsent((n % 3) + ":" + response.getStatusCode().value(), k -> new AtomicInteger())
                        .incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // Everything succeeds (a busy 503 is retried with the same key, as a client would): wallets start
        // with ₹1,000 and spend at most ₹110 each.
        assertThat(outcomes.keySet()).as("outcomes %s", outcomes).containsOnly("0:201", "1:201", "2:201");
        int payments = outcomes.get("0:201").get();
        int topUps = outcomes.get("1:201").get();

        assertThat(balanceOf(shop.accountId())).isEqualTo(payments * 1_000L);
        assertThat(balanceOf(clearingAccountId())).isEqualTo(clearingBefore - topUps * 500L);
        long walletsTotal = wallets.stream().mapToLong(this::balanceOf).sum();
        assertThat(walletsTotal).isEqualTo(WALLETS * 100_000L + topUps * 500L - payments * 1_000L);

        assertThat(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM accounts a
                        WHERE a.balance <> (SELECT COALESCE(SUM(amount), 0) FROM postings p WHERE p.account_id = a.id)
                        """).query(Long.class).single())
                .as("accounts whose cached balance drifted from their postings").isZero();
    }
}
