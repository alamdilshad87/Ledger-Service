package dev.ledger.payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import dev.ledger.common.NotFoundException;
import dev.ledger.common.UnprocessableException;
import dev.ledger.ledger.EntryType;
import dev.ledger.ledger.LedgerService;
import dev.ledger.ledger.NewJournalEntry;
import dev.ledger.ledger.PostedEntry;
import dev.ledger.ledger.Posting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refunds are new REFUND entries that move money back (merchant → wallet) and point at the payment.
 * The original PAYMENT entry is never touched: the ledger is append-only.
 */
@Service
public class RefundService {

    private final PaymentRepository payments;
    private final LedgerService ledger;

    public RefundService(PaymentRepository payments, LedgerService ledger) {
        this.payments = payments;
        this.ledger = ledger;
    }

    /**
     * Refunds {@code amount} paise, or everything still refundable when {@code amount} is null.
     * <p>
     * The payment row is locked first, so two concurrent refunds can't both see the same refundable
     * amount. Lock order is payment, then accounts (inside the ledger); nothing locks them the other
     * way round, so this can't deadlock with payments or transfers.
     */
    @Transactional
    public RefundResult refund(UUID paymentId, Long amount, String note, String idempotencyKey) {
        Payment payment = payments.lockForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "Payment " + paymentId + " not found"));
        if (payment.refundable() == 0) {
            throw new UnprocessableException("PAYMENT_FULLY_REFUNDED", "This payment has already been fully refunded");
        }
        long refund = amount == null ? payment.refundable() : amount;
        if (refund > payment.refundable()) {
            throw new UnprocessableException("REFUND_EXCEEDS_PAYMENT",
                    "Refund of " + refund + " paise exceeds the " + payment.refundable() + " paise still refundable");
        }

        PostedEntry posted = ledger.post(NewJournalEntry.referencing(payment.id(), EntryType.REFUND, idempotencyKey, note,
                List.of(Posting.debit(payment.merchantAccountId(), refund), Posting.credit(payment.walletAccountId(), refund))));
        payments.addRefunded(payment.id(), refund);

        Payment after = new Payment(payment.id(), payment.walletAccountId(), payment.merchantId(),
                payment.merchantAccountId(), payment.amount(), payment.refundedAmount() + refund, payment.createdAt());
        return new RefundResult(posted.id(), payment.id(), refund, after.refundedAmount(), after.status(), posted.createdAt());
    }

    public record RefundResult(UUID refundId, UUID paymentId, long amount, long refundedAmount, String paymentStatus,
                               OffsetDateTime createdAt) {
    }
}
