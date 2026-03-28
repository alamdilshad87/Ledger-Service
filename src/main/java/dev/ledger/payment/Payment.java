package dev.ledger.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Payment(
        UUID id,
        long walletAccountId,
        UUID merchantId,
        long merchantAccountId,
        long amount,
        long refundedAmount,
        OffsetDateTime createdAt) {

    public long refundable() {
        return amount - refundedAmount;
    }

    public String status() {
        if (refundedAmount == 0) {
            return "COMPLETED";
        }
        return refundedAmount == amount ? "REFUNDED" : "PARTIALLY_REFUNDED";
    }
}
