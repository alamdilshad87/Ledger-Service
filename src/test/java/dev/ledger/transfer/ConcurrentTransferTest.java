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
import org.springframework.http.HttpStatusCode;

/**
 * Week 3: many transfers hitting the same wallets at the same instant.
 * Each test fires {@value #THREADS} requests released together by a start gate, then checks the ledger.
 * Repeated 20 times because a race that passes once proves very little.
 */
class ConcurrentTransferTest extends ApiTestSupport {

    private static final int THREADS = 200;

    @RepeatedTest(20)
    void twoHundredThreadsDrainingOneWalletNeverOverdraw() throws Exception {
        // Recipients created both before and after the sender, so lock order differs between requests.
        List<Long> recipients = new ArrayList<>(List.of(newWallet(), newWallet()));
        long sender = newWalletWith(100_000);               // ₹1,000
        recipients.addAll(List.of(newWallet(), newWallet()));
        long amount = 1_000;                                // ₹10 each, so exactly 100 can succeed

        Map<Integer, AtomicInteger> statuses = fire(i -> transfer(sender, recipients.get(i % recipients.size()), amount));

        assertThat(statuses.keySet()).as("status codes seen: %s", statuses).containsOnly(201, 402);
        assertThat(statuses.get(201).get()).isEqualTo(100);
        assertThat(statuses.get(402).get()).isEqualTo(100);
        assertThat(balanceOf(sender)).isZero();
        assertThat(recipients.stream().mapToLong(this::balanceOf).sum()).isEqualTo(100_000);
        assertLedgerBalanced();
    }

    @RepeatedTest(20)
    void oppositeDirectionTransfersNeverDeadlock() throws Exception {
        long asha = newWalletWith(1_000_000);
        long ravi = newWalletWith(1_000_000);

        // Even requests send Asha → Ravi, odd ones Ravi → Asha: the classic lock-order deadlock.
        Map<Integer, AtomicInteger> statuses = fire(i -> i % 2 == 0 ? transfer(asha, ravi, 100) : transfer(ravi, asha, 100));

        assertThat(statuses.keySet()).as("status codes seen: %s", statuses).containsOnly(201);
        assertThat(statuses.get(201).get()).isEqualTo(THREADS);
        assertThat(balanceOf(asha)).isEqualTo(1_000_000);
        assertThat(balanceOf(ravi)).isEqualTo(1_000_000);
        assertLedgerBalanced();
    }

    /**
     * Sends one transfer the way a well-behaved client does: if the service answers 503 (busy: 200 requests
     * are queueing on the same two rows), wait and retry with the <em>same</em> Idempotency-Key. Whatever
     * the number of attempts, the transfer happens at most once, so the exact counts below still hold.
     * The 90 s budget outlasts the 60 s idempotency lease, the worst case if a busy attempt couldn't
     * release its key.
     */
    private HttpStatusCode transfer(long from, long to, long amount) {
        String key = java.util.UUID.randomUUID().toString();
        var body = Map.of("fromAccountId", from, "toAccountId", to, "amount", amount);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            HttpStatusCode status = post("/v1/transfers", body, key).getStatusCode();
            if (status.value() != 503 && status.value() != 409) {   // 409: an earlier attempt is still running
                return status;
            }
            busyRetries.incrementAndGet();
            try {
                Thread.sleep(50 + (long) (Math.random() * 200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
        }
        throw new AssertionError("Transfer still busy after 90 s of retries");
    }

    private final AtomicInteger busyRetries = new AtomicInteger();

    /** Runs {@value #THREADS} calls that all start at the same moment; returns a count per HTTP status. */
    private Map<Integer, AtomicInteger> fire(Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    int status = call.run(n).value();
                    statuses.computeIfAbsent(status, s -> new AtomicInteger()).incrementAndGet();
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }

    private void assertLedgerBalanced() {
        assertThat(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM accounts a
                        WHERE a.balance <> (SELECT COALESCE(SUM(amount), 0) FROM postings p WHERE p.account_id = a.id)
                        """).query(Long.class).single())
                .as("accounts whose cached balance drifted from their postings").isZero();
    }

    @FunctionalInterface
    private interface Call {
        HttpStatusCode run(int i);
    }
}
