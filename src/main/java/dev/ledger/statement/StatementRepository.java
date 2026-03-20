package dev.ledger.statement;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StatementRepository {

    private final JdbcClient jdbc;

    public StatementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Postings for one account, newest first, strictly older than {@code beforePostingId}.
     * Keyset pagination on the posting id: stable while new postings arrive, and each page is
     * one index range scan on (account_id, id DESC) however deep you page.
     */
    public List<StatementLine> page(long accountId, long beforePostingId, int limit) {
        return jdbc.sql("""
                        SELECT p.id, p.entry_id, j.type, p.amount, j.note, p.created_at
                        FROM postings p
                        JOIN journal_entries j ON j.id = p.entry_id
                        WHERE p.account_id = :account AND p.id < :before
                        ORDER BY p.id DESC
                        LIMIT :limit
                        """)
                .param("account", accountId)
                .param("before", beforePostingId)
                .param("limit", limit)
                .query((rs, n) -> new StatementLine(
                        rs.getLong("id"),
                        rs.getObject("entry_id", UUID.class),
                        rs.getString("type"),
                        rs.getLong("amount"),
                        rs.getString("note"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    /** {@code amount} in paise: negative = money out, positive = money in. */
    public record StatementLine(
            long postingId, UUID entryId, String entryType, long amount, String note, OffsetDateTime createdAt) {
    }
}
