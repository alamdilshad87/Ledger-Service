package dev.ledger.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import dev.ledger.common.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of one journal entry with all its postings: the audit trail for any money movement. */
@RestController
public class JournalEntryController {

    private final JdbcClient jdbc;

    public JournalEntryController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/journal-entries/{id}")
    public JournalEntryView get(@PathVariable UUID id) {
        JournalEntryView entry = jdbc.sql("""
                        SELECT id, type, reference_id, note, created_at FROM journal_entries WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new JournalEntryView(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getObject("reference_id", UUID.class),
                        rs.getString("note"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        List.of()))
                .optional()
                .orElseThrow(() -> new NotFoundException("ENTRY_NOT_FOUND", "Journal entry " + id + " not found"));

        List<PostingView> postings = jdbc.sql("SELECT account_id, amount FROM postings WHERE entry_id = :id ORDER BY id")
                .param("id", id)
                .query((rs, n) -> new PostingView(rs.getLong("account_id"), rs.getLong("amount")))
                .list();

        return new JournalEntryView(entry.id(), entry.type(), entry.referenceId(), entry.note(), entry.createdAt(), postings);
    }

    public record JournalEntryView(
            UUID id, String type, UUID referenceId, String note, OffsetDateTime createdAt, List<PostingView> postings) {
    }

    public record PostingView(long accountId, long amount) {
    }
}
