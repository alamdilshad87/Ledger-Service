package dev.ledger.topup;

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

/** Adds money to a wallet. The money comes out of the system clearing account, so it always has a source. */
@Service
public class TopUpService {

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final OutboxWriter outbox;

    public TopUpService(AccountRepository accounts, LedgerService ledger, OutboxWriter outbox) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    @Transactional
    public TopUpResult topUp(long walletId, long amount, String idempotencyKey) {
        Account wallet = accounts.findById(walletId)
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "Account " + walletId + " not found"));
        if (wallet.type() != AccountType.USER_WALLET) {
            throw new UnprocessableException("NOT_A_WALLET", "Only user wallets can be topped up");
        }

        long clearing = accounts.clearingAccountId();
        PostedEntry posted = ledger.post(NewJournalEntry.of(EntryType.TOPUP, idempotencyKey, null,
                List.of(Posting.debit(clearing, amount), Posting.credit(walletId, amount))));

        outbox.append("topup", posted.id(), "topup.completed", Map.of(
                "entryId", posted.id(),
                "accountId", walletId,
                "amount", amount,
                "currency", wallet.currency()));

        return new TopUpResult(posted.id(), "COMPLETED", posted.balanceOf(walletId), posted.createdAt());
    }

    public record TopUpResult(UUID entryId, String status, long balance, OffsetDateTime createdAt) {
    }
}
