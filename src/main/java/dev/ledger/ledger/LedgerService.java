package dev.ledger.ledger;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.ledger.account.AccountRepository;
import dev.ledger.common.InsufficientFundsException;
import dev.ledger.common.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place money moves. Writes the journal entry, its postings and the cached
 * account balances in one transaction, so either all of it commits or none of it does.
 */
@Service
public class LedgerService {

    private final JdbcClient jdbc;
    private final AccountRepository accounts;

    public LedgerService(JdbcClient jdbc, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Transactional
    public PostedEntry post(NewJournalEntry entry) {
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

    private long applyToBalance(Posting posting) {
        try {
            return accounts.applyDelta(posting.accountId(), posting.amount())
                    .orElseThrow(() -> new NotFoundException(
                            "ACCOUNT_NOT_FOUND", "Account " + posting.accountId() + " not found"));
        } catch (DataIntegrityViolationException e) {
            // Last line of defence: the CHECK constraint refused to let a wallet go negative.
            if (String.valueOf(e.getMessage()).contains("non_negative_wallet")) {
                throw new InsufficientFundsException();
            }
            throw e;
        }
    }
}
