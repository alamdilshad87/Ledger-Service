package dev.ledger.ledger;

import java.time.OffsetDateTime;
import java.util.Comparator;
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
 *   <li>locks the user wallets in the entry, in ascending id order</li>
 *   <li>checks no wallet would go negative, against the locked (current) balances</li>
 *   <li>writes the journal entry and its postings</li>
 *   <li>updates cached balances: wallets first, hot house accounts (merchant, clearing) last</li>
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
        Map<Long, Account> involved = lockWalletsInIdOrder(entry);
        for (Posting posting : entry.postings()) {
            Account account = involved.get(posting.accountId());
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

        for (Posting posting : entry.postings()) {
            jdbc.sql("INSERT INTO postings (entry_id, account_id, amount, created_at) VALUES (:entry, :account, :amount, :at)")
                    .param("entry", entry.id())
                    .param("account", posting.accountId())
                    .param("amount", posting.amount())
                    .param("at", createdAt)
                    .update();
        }

        // Balances: wallets first (already locked), then house accounts, both by ascending id. A house
        // account's row lock is taken by this UPDATE, at the very end of the ledger work, so it is held
        // only until commit and never while this transaction waits for anything else.
        Map<Long, Long> balancesAfter = new LinkedHashMap<>();
        entry.postings().stream()
                .sorted(Comparator.comparing((Posting p) -> involved.get(p.accountId()).type() != AccountType.USER_WALLET)
                        .thenComparingLong(Posting::accountId))
                .forEach(posting -> balancesAfter.put(posting.accountId(), applyToBalance(posting)));
        return new PostedEntry(entry.id(), createdAt, Map.copyOf(balancesAfter));
    }

    /**
     * Row-locks the user wallets in the entry, lowest id first, and reads every other account unlocked.
     * <p>
     * Wallets need the lock: their balance is checked, and the check must not go stale before the write.
     * The fixed order prevents deadlocks (Asha→Ravi and Ravi→Asha both lock the lower id first, so the
     * second queues instead of each holding one lock and waiting for the other).
     * <p>
     * House accounts (merchant, clearing) are <b>not</b> locked here: they have no balance check, and they
     * are hot (every payment to a merchant, every top-up). Locking them up front made each transaction
     * queue for the one hot row while still holding its wallet locks, which dragged unrelated wallets
     * into the queue and exhausted the connection pool at ~40 req/s (docs/operations.md).
     * Every transaction takes wallet locks before house-account locks, so the global lock order still holds.
     */
    private Map<Long, Account> lockWalletsInIdOrder(NewJournalEntry entry) {
        Map<Long, Account> involved = new TreeMap<>();
        for (Posting posting : entry.postings()) {
            involved.put(posting.accountId(), accounts.findById(posting.accountId())
                    .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "Account " + posting.accountId() + " not found")));
        }
        for (Map.Entry<Long, Account> e : involved.entrySet()) {   // TreeMap: ascending id
            if (e.getValue().type() == AccountType.USER_WALLET) {
                e.setValue(accounts.lockForUpdate(e.getKey()).orElseThrow());
            }
        }
        return involved;
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
