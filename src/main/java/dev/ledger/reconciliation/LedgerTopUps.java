package dev.ledger.reconciliation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Top-ups the ledger recorded on one business day: what the bank statement should list. */
@Component
public class LedgerTopUps {

    private final JdbcClient jdbc;
    private final ReconciliationProperties properties;

    public LedgerTopUps(JdbcClient jdbc, ReconciliationProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** Journal entry id → amount credited to the wallet, in paise, ordered by time. */
    public Map<String, Long> on(LocalDate date) {
        OffsetDateTime from = date.atStartOfDay(properties.zone()).toOffsetDateTime();
        OffsetDateTime to = date.plusDays(1).atStartOfDay(properties.zone()).toOffsetDateTime();
        Map<String, Long> topUps = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT j.id::text AS reference, p.amount
                        FROM journal_entries j
                        JOIN postings p ON p.entry_id = j.id AND p.amount > 0
                        WHERE j.type = 'TOPUP' AND j.created_at >= :from AND j.created_at < :to
                        ORDER BY j.created_at, j.id
                        """)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> topUps.put(rs.getString("reference"), rs.getLong("amount")))
                .list();
        return topUps;
    }
}
