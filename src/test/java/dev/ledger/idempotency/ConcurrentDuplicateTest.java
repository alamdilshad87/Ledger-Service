package dev.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.RepeatedTest;

/** Week 4 "done when": the same transfer sent 100 times in parallel creates exactly one entry. */
class ConcurrentDuplicateTest extends ApiTestSupport {

    private static final int COPIES = 100;

    @RepeatedTest(10)
    void hundredParallelCopiesOfOneTransferMoveMoneyOnce() throws Exception {
        long asha = newWalletWith(100_000);
        long ravi = newWallet();
        String key = UUID.randomUUID().toString();
        var body = Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 25_000);

        ExecutorService pool = Executors.newFixedThreadPool(COPIES);
        CountDownLatch go = new CountDownLatch(1);
        Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();
        Set<String> entryIds = ConcurrentHashMap.newKeySet();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < COPIES; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    var response = post("/v1/transfers", body, key);
                    statuses.computeIfAbsent(response.getStatusCode().value(), s -> new AtomicInteger()).incrementAndGet();
                    if (response.getStatusCode().value() == 201) {
                        entryIds.add((String) response.getBody().get("entryId"));
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Every copy either got the one real result (first or replayed) or was told to retry.
        assertThat(statuses.keySet()).as("statuses %s", statuses).isSubsetOf(201, 409);
        assertThat(statuses.get(201)).as("statuses %s", statuses).isNotNull();
        assertThat(entryIds).as("every 201 carries the same entry id").hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = :k").param("k", key)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(balanceOf(asha)).isEqualTo(75_000);
        assertThat(balanceOf(ravi)).isEqualTo(25_000);
    }
}
