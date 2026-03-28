package dev.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentApiTest extends ApiTestSupport {

    @Test
    void paymentMovesMoneyFromWalletToMerchant() {
        long asha = newWalletWith(100_000);
        Merchant shop = newMerchant();

        var response = post("/v1/payments", Map.of("fromAccountId", asha, "merchantId", shop.id(), "amount", 45_000, "note", "Groceries"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED").containsKey("paymentId");
        assertThat(asLong(response.getBody().get("walletBalance"))).isEqualTo(55_000);
        assertThat(balanceOf(asha)).isEqualTo(55_000);
        assertThat(balanceOf(shop.accountId())).isEqualTo(45_000);
    }

    @Test
    void paymentCanBeFetchedById() {
        long asha = newWalletWith(100_000);
        Merchant shop = newMerchant();
        UUID paymentId = pay(asha, shop, 12_000);

        var body = get("/v1/payments/" + paymentId).getBody();

        assertThat(body).containsEntry("paymentId", paymentId.toString())
                .containsEntry("status", "COMPLETED")
                .containsEntry("merchantId", shop.id().toString())
                .containsEntry("amount", 12_000)
                .containsEntry("refundedAmount", 0);
        assertThat(asLong(body.get("walletAccountId"))).isEqualTo(asha);
    }

    @Test
    void paymentIdIsTheJournalEntryId() {
        long asha = newWalletWith(100_000);
        UUID paymentId = pay(asha, newMerchant(), 1_000);

        assertThat(get("/v1/journal-entries/" + paymentId).getBody()).containsEntry("type", "PAYMENT");
    }

    @Test
    void insufficientFundsIs402() {
        long asha = newWalletWith(1_000);
        Merchant shop = newMerchant();

        var response = post("/v1/payments", Map.of("fromAccountId", asha, "merchantId", shop.id(), "amount", 1_001));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(balanceOf(asha)).isEqualTo(1_000);
        assertThat(balanceOf(shop.accountId())).isZero();
    }

    @Test
    void unknownMerchantIs404() {
        long asha = newWalletWith(1_000);

        var response = post("/v1/payments", Map.of("fromAccountId", asha, "merchantId", UUID.randomUUID(), "amount", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "MERCHANT_NOT_FOUND");
    }

    @Test
    void merchantCannotPayAnotherMerchant() {
        Merchant a = newMerchant();
        Merchant b = newMerchant();

        var response = post("/v1/payments", Map.of("fromAccountId", a.accountId(), "merchantId", b.id(), "amount", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "NOT_A_WALLET");
    }

    @Test
    void paymentsNeedAnIdempotencyKeyAndAreReplayed() {
        long asha = newWalletWith(10_000);
        Merchant shop = newMerchant();
        var body = Map.of("fromAccountId", asha, "merchantId", shop.id(), "amount", 2_000);
        String key = UUID.randomUUID().toString();

        assertThat(post("/v1/payments", body, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var first = post("/v1/payments", body, key);
        var retry = post("/v1/payments", body, key);

        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(balanceOf(asha)).isEqualTo(8_000);
    }

    @Test
    void unknownPaymentIs404() {
        assertThat(get("/v1/payments/" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
