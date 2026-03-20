package dev.ledger.transfer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import dev.ledger.account.Account;
import dev.ledger.account.AccountRepository;
import dev.ledger.account.AccountType;
import dev.ledger.common.InsufficientFundsException;
import dev.ledger.common.NotFoundException;
import dev.ledger.common.UnprocessableException;
import dev.ledger.ledger.EntryType;
import dev.ledger.ledger.LedgerService;
import dev.ledger.ledger.NewJournalEntry;
import dev.ledger.ledger.PostedEntry;
import dev.ledger.ledger.Posting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Moves money between two user wallets as one journal entry. */
@Service
public class TransferService {

    private final AccountRepository accounts;
    private final LedgerService ledger;

    public TransferService(AccountRepository accounts, LedgerService ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional
    public TransferResult transfer(long fromId, long toId, long amount, String note, String idempotencyKey) {
        if (fromId == toId) {
            throw new UnprocessableException("SAME_ACCOUNT", "Cannot transfer to the same account");
        }
        Account from = wallet(fromId);
        Account to = wallet(toId);
        if (!from.currency().equals(to.currency())) {
            throw new UnprocessableException("CURRENCY_MISMATCH", "Both wallets must use the same currency");
        }

        // Read-then-write without a row lock: two concurrent transfers can both pass this check.
        // The non_negative_wallet CHECK still stops the balance going below zero.
        // Week 3 reproduces that race and fixes it with SELECT ... FOR UPDATE in id order.
        if (from.balance() < amount) {
            throw new InsufficientFundsException();
        }

        PostedEntry posted = ledger.post(NewJournalEntry.of(EntryType.TRANSFER, idempotencyKey, note,
                List.of(Posting.debit(fromId, amount), Posting.credit(toId, amount))));

        return new TransferResult(posted.id(), "COMPLETED", posted.balanceOf(fromId), posted.createdAt());
    }

    private Account wallet(long id) {
        Account account = accounts.findById(id)
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "Account " + id + " not found"));
        if (account.type() != AccountType.USER_WALLET) {
            throw new UnprocessableException("NOT_A_WALLET", "Account " + id + " is not a user wallet");
        }
        return account;
    }

    public record TransferResult(UUID entryId, String status, long fromBalance, OffsetDateTime createdAt) {
    }
}
