package dev.ledger.transfer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
import dev.ledger.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Moves money between two user wallets as one journal entry. */
@Service
public class TransferService {

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final OutboxWriter outbox;

    public TransferService(AccountRepository accounts, LedgerService ledger, OutboxWriter outbox) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.outbox = outbox;
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

        // No balance check here: a balance read outside the lock can be stale by the time we write.
        // LedgerService locks both rows (lower id first) and checks the balance under the lock.

        PostedEntry posted = ledger.post(NewJournalEntry.of(EntryType.TRANSFER, idempotencyKey, note,
                List.of(Posting.debit(fromId, amount), Posting.credit(toId, amount))));

        outbox.append("transfer", posted.id(), "transfer.completed", Map.of(
                "entryId", posted.id(),
                "fromAccountId", fromId,
                "toAccountId", toId,
                "amount", amount,
                "currency", from.currency()));

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
