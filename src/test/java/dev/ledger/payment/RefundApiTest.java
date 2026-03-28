package dev.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RefundApiTest extends ApiTestSupport {

    private String refundsPath(UUID paymentId) {
        return "/v1/payments/" + paymentId + "/refunds";
    }

    @Test
    void partialRefundsThenTheRestReturnEverything() {
        long asha = newWalletWith(100_000);
        Merchant shop = newMerchant();
        UUID payment = pay(asha, shop, 40_000);

        var first = post(refundsPath(payment), Map.of("amount", 15_000));
        var rest = post(refundsPath(payment), Map.of());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).containsEntry("amount", 15_000).containsEntry("paymentStatus", "PARTIALLY_REFUNDED");
        assertThat(rest.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rest.getBody()).containsEntry("amount", 25_000).containsEntry("refundedAmount", 40_000)
                .containsEntry("paymentStatus", "REFUNDED");
        assertThat(balanceOf(asha)).isEqualTo(100_000);
        assertThat(balanceOf(shop.accountId())).isZero();
    }

    @SuppressWarnings("unchecked")
    @Test
    void refundsAreReversingEntriesLinkedToThePayment() {
        long asha = newWalletWith(100_000);
        Merchant shop = newMerchant();
        UUID payment = pay(asha, shop, 10_000);
        String refundId = (String) post(refundsPath(payment), Map.of("amount", 4_000)).getBody().get("refundId");

        var entry = get("/v1/journal-entries/" + refundId).getBody();
        assertThat(entry).containsEntry("type", "REFUND").containsEntry("referenceId", payment.toString());
        var postings = (List<Map<String, Object>>) entry.get("postings");
        assertThat(postings).anySatisfy(p -> {
            assertThat(asLong(p.get("accountId"))).isEqualTo(shop.accountId());
            assertThat(asLong(p.get("amount"))).isEqualTo(-4_000);
        });

        // The original payment entry is untouched; the payment view lists the refund.
        var view = get("/v1/payments/" + payment).getBody();
        assertThat(view).containsEntry("status", "PARTIALLY_REFUNDED").containsEntry("refundedAmount", 4_000);
        assertThat((List<Map<String, Object>>) view.get("refunds")).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("refundId", refundId).containsEntry("amount", 4_000));
    }

    @Test
    void refundMoreThanRemainingIs422() {
        long asha = newWalletWith(100_000);
        UUID payment = pay(asha, newMerchant(), 10_000);
        post(refundsPath(payment), Map.of("amount", 6_000));

        var response = post(refundsPath(payment), Map.of("amount", 4_001));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "REFUND_EXCEEDS_PAYMENT");
        assertThat(balanceOf(asha)).isEqualTo(96_000);
    }

    @Test
    void refundingAFullyRefundedPaymentIs422() {
        long asha = newWalletWith(100_000);
        UUID payment = pay(asha, newMerchant(), 10_000);
        post(refundsPath(payment), Map.of());

        var again = post(refundsPath(payment), Map.of());

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(again.getBody()).containsEntry("code", "PAYMENT_FULLY_REFUNDED");
    }

    @Test
    void unknownPaymentIs404AndZeroAmountIs400() {
        assertThat(post(refundsPath(UUID.randomUUID()), Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        UUID payment = pay(newWalletWith(10_000), newMerchant(), 1_000);
        assertThat(post(refundsPath(payment), Map.of("amount", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refundsNeedAnIdempotencyKeyAndRetriesDontRefundTwice() {
        long asha = newWalletWith(100_000);
        UUID payment = pay(asha, newMerchant(), 10_000);
        String key = UUID.randomUUID().toString();

        assertThat(post(refundsPath(payment), Map.of("amount", 1_000), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        post(refundsPath(payment), Map.of("amount", 1_000), key);
        var retry = post(refundsPath(payment), Map.of("amount", 1_000), key);

        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balanceOf(asha)).isEqualTo(91_000);
    }

    @Test
    void fiftyConcurrentRefundsNeverExceedThePayment() throws Exception {
        long asha = newWalletWith(100_000);
        Merchant shop = newMerchant();
        UUID payment = pay(asha, shop, 10_000);              // ₹100, refunded ₹10 at a time: 10 can succeed

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch go = new CountDownLatch(1);
        Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                int status = post(refundsPath(payment), Map.of("amount", 1_000)).getStatusCode().value();
                statuses.computeIfAbsent(status, s -> new AtomicInteger()).incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(statuses.keySet()).as("statuses %s", statuses).containsOnly(201, 422);
        assertThat(statuses.get(201).get()).isEqualTo(10);
        assertThat(get("/v1/payments/" + payment).getBody()).containsEntry("refundedAmount", 10_000);
        assertThat(balanceOf(asha)).isEqualTo(100_000);
        assertThat(balanceOf(shop.accountId())).isZero();
    }
}
