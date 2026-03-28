package dev.ledger.payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import dev.ledger.account.Account;
import dev.ledger.account.AccountRepository;
import dev.ledger.account.AccountType;
import dev.ledger.common.NotFoundException;
import dev.ledger.common.UnprocessableException;
import dev.ledger.ledger.EntryType;
import dev.ledger.ledger.LedgerService;
import dev.ledger.ledger.NewJournalEntry;
import dev.ledger.ledger.PostedEntry;
import dev.ledger.ledger.Posting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Wallet → merchant payment: one PAYMENT entry plus a payments row to track refunds against. */
@Service
public class PaymentService {

    private final AccountRepository accounts;
    private final PaymentRepository payments;
    private final LedgerService ledger;

    public PaymentService(AccountRepository accounts, PaymentRepository payments, LedgerService ledger) {
        this.accounts = accounts;
        this.payments = payments;
        this.ledger = ledger;
    }

    @Transactional
    public PaymentResult pay(long walletId, UUID merchantId, long amount, String note, String idempotencyKey) {
        Account wallet = accounts.findById(walletId)
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "Account " + walletId + " not found"));
        if (wallet.type() != AccountType.USER_WALLET) {
            throw new UnprocessableException("NOT_A_WALLET", "Payments must come from a user wallet");
        }
        long merchantAccountId = accounts.merchantAccountId(merchantId)
                .orElseThrow(() -> new NotFoundException("MERCHANT_NOT_FOUND", "Merchant " + merchantId + " not found"));

        PostedEntry posted = ledger.post(NewJournalEntry.of(EntryType.PAYMENT, idempotencyKey, note,
                List.of(Posting.debit(walletId, amount), Posting.credit(merchantAccountId, amount))));

        Payment payment = new Payment(posted.id(), walletId, merchantId, merchantAccountId, amount, 0, posted.createdAt());
        payments.insert(payment);
        return new PaymentResult(payment.id(), payment.status(), amount, posted.balanceOf(walletId), posted.createdAt());
    }

    public record PaymentResult(UUID paymentId, String status, long amount, long walletBalance, OffsetDateTime createdAt) {
    }
}
