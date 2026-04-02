package dev.ledger.ledger;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import dev.ledger.account.Account;
import dev.ledger.account.AccountRepository;
import dev.ledger.account.AccountType;
import dev.ledger.common.InsufficientFundsException;
import dev.ledger.common.LedgerMetrics;
import dev.ledger.common.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only place money moves. In one transaction it:
 * <ol>
 *   <li>locks every account in the entry, in ascending id order</li>
 *   <li>checks no user wallet would go negative, against the locked (current) balances</li>
 *   <li>writes the journal entry, its postings and the new cached balances</li>
 * </ol>
 * Either all of it commits or none of it does.
 */
@Service
public class LedgerService {

    private final JdbcClient jdbc;
    private final AccountRepository accounts;
    private final LedgerMetrics metrics;

    public LedgerService(JdbcClient jdbc, AccountRepository accounts, LedgerMetrics metrics) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.metrics = metrics;
    }

    @Transactional
    public PostedEntry post(NewJournalEntry entry) {
        Map<Long, Account> locked = lockInIdOrder(entry);
        for (Posting posting : entry.postings()) {
            Account account = locked.get(posting.accountId());
            if (account.type() == AccountType.USER_WALLET && account.balance() + posting.amount() < 0) {
                metrics.insufficientFunds();
                throw new InsufficientFundsException();
            }
        }
        // Count the entry only once it has really committed; a rolled-back entry moved no money.
        long moved = entry.postings().stream().mapToLong(Posting::amount).filter(a -> a > 0).sum();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.entryPosted(entry.type().name(), moved);
            }
        });

        OffsetDateTime createdAt = jdbc.sql("""
                        INSERT INTO journal_entries (id, type, reference_id, idempotency_key, note)
                        VALUES (:id, :type, :referenceId, :key, :note)
                        RETURNING created_at
                        """)
                .param("id", entry.id())
                .param("type", entry.type().name())
                .param("referenceId", entry.referenceId())
                .param("key", entry.idempotencyKey())
                .param("note", entry.note())
                .query(OffsetDateTime.class)
                .single();

        Map<Long, Long> balancesAfter = new LinkedHashMap<>();
        for (Posting posting : entry.postings()) {
            long balance = applyToBalance(posting);
            jdbc.sql("INSERT INTO postings (entry_id, account_id, amount, created_at) VALUES (:entry, :account, :amount, :at)")
                    .param("entry", entry.id())
                    .param("account", posting.accountId())
                    .param("amount", posting.amount())
                    .param("at", createdAt)
                    .update();
            balancesAfter.put(posting.accountId(), balance);
        }
        return new PostedEntry(entry.id(), createdAt, Map.copyOf(balancesAfter));
    }

    /**
     * Takes a row lock on every account in the entry, lowest id first.
     * <p>
     * The fixed order is what prevents deadlocks: if Asha→Ravi locked Asha then Ravi while Ravi→Asha
     * locked Ravi then Asha, each could hold one lock and wait forever for the other. With one global
     * order the second transaction simply queues behind the first.
     */
    private Map<Long, Account> lockInIdOrder(NewJournalEntry entry) {
        Map<Long, Account> locked = new TreeMap<>();
        entry.postings().stream()
                .map(Posting::accountId)
                .sorted()
                .forEach(id -> locked.put(id, accounts.lockForUpdate(id)
                        .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "Account " + id + " not found"))));
        return locked;
    }

    private long applyToBalance(Posting posting) {
        try {
            return accounts.applyDelta(posting.accountId(), posting.amount())
                    .orElseThrow(() -> new NotFoundException(
                            "ACCOUNT_NOT_FOUND", "Account " + posting.accountId() + " not found"));
        } catch (DataIntegrityViolationException e) {
            // Should be unreachable now that balances are checked under lock; the CHECK constraint
            // stays as the last line of defence against a future bug.
            if (String.valueOf(e.getMessage()).contains("non_negative_wallet")) {
                throw new InsufficientFundsException();
            }
            throw e;
        }
    }
}
