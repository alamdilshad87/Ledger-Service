package dev.ledger.reconciliation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.reconciliation.ReconciliationReport.Mismatch;
import dev.ledger.reconciliation.ReconciliationReport.MismatchType;
import dev.ledger.reconciliation.ReconciliationReport.Status;
import dev.ledger.reconciliation.ReconciliationReport.Summary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Checks the ledger against itself and against the bank, for one business day.
 * <ol>
 *   <li><b>Internal:</b> all postings sum to zero, and every cached balance equals its postings.
 *       These catch bugs and manual database edits that tests missed.</li>
 *   <li><b>External:</b> every top-up we recorded that day appears on the bank statement with the same
 *       amount, and every bank credit has a top-up. This catches money we think we have but don't.</li>
 * </ol>
 * The report is stored in {@code reconciliation_runs} whatever the outcome.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final JdbcClient jdbc;
    private final LedgerTopUps topUps;
    private final ReconciliationProperties properties;
    private final ObjectMapper json;

    public ReconciliationService(JdbcClient jdbc, LedgerTopUps topUps, ReconciliationProperties properties,
                                 ObjectMapper json) {
        this.jdbc = jdbc;
        this.topUps = topUps;
        this.properties = properties;
        this.json = json;
    }

    public ReconciliationReport run(LocalDate date) {
        List<Mismatch> mismatches = new ArrayList<>();

        // 1. Internal consistency.
        long postingsTotal = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single();
        if (postingsTotal != 0) {
            mismatches.add(new Mismatch(MismatchType.LEDGER_UNBALANCED, "postings", 0L, postingsTotal,
                    "All postings must sum to 0"));
        }
        long accountsChecked = jdbc.sql("SELECT COUNT(*) FROM accounts").query(Long.class).single();
        jdbc.sql("""
                        SELECT a.id, a.balance, COALESCE(SUM(p.amount), 0) AS from_postings
                        FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
                        GROUP BY a.id, a.balance
                        HAVING a.balance <> COALESCE(SUM(p.amount), 0)
                        ORDER BY a.id
                        """)
                .query((rs, n) -> mismatches.add(new Mismatch(MismatchType.BALANCE_DRIFT, "account:" + rs.getLong("id"),
                        rs.getLong("from_postings"), rs.getLong("balance"), "Cached balance differs from its postings")))
                .list();

        // 2. Ledger vs bank for the day.
        Map<String, Long> ledger = topUps.on(date);
        long ledgerTotal = ledger.values().stream().mapToLong(Long::longValue).sum();
        Path file = properties.statementFor(date);
        long bankCount = 0;
        long bankTotal = 0;
        Status status;

        if (!Files.exists(file)) {
            status = Status.STATEMENT_MISSING;
        } else {
            BankStatement.Parsed statement = read(file);
            statement.badLines().forEach(bad -> mismatches.add(new Mismatch(MismatchType.MALFORMED_BANK_LINE,
                    "line " + bad.lineNumber(), null, null, bad.problem() + ": " + bad.content())));

            Map<String, Long> bank = new LinkedHashMap<>();
            Map<String, Integer> seen = new HashMap<>();
            for (BankStatement.Line line : statement.lines()) {
                bankCount++;
                bankTotal += line.amountPaise();
                if (seen.merge(line.reference(), 1, Integer::sum) > 1) {
                    mismatches.add(new Mismatch(MismatchType.DUPLICATE_BANK_LINE, line.reference(), null,
                            line.amountPaise(), "Reference appears more than once on the statement"));
                } else {
                    bank.put(line.reference(), line.amountPaise());
                }
            }

            ledger.forEach((reference, amount) -> {
                Long bankAmount = bank.get(reference);
                if (bankAmount == null) {
                    mismatches.add(new Mismatch(MismatchType.MISSING_IN_BANK, reference, amount, null,
                            "Top-up recorded in the ledger but not on the bank statement"));
                } else if (!bankAmount.equals(amount)) {
                    mismatches.add(new Mismatch(MismatchType.AMOUNT_MISMATCH, reference, amount, bankAmount,
                            "Ledger and bank disagree on the amount"));
                }
            });
            bank.forEach((reference, amount) -> {
                if (!ledger.containsKey(reference)) {
                    mismatches.add(new Mismatch(MismatchType.MISSING_IN_LEDGER, reference, null, amount,
                            "Bank credit with no matching top-up"));
                }
            });
            status = mismatches.isEmpty() ? Status.MATCHED : Status.MISMATCHED;
        }

        ReconciliationReport report = new ReconciliationReport(UUID.randomUUID(), date, status, OffsetDateTime.now(),
                file.toString(),
                new Summary(postingsTotal, accountsChecked, ledger.size(), ledgerTotal, bankCount, bankTotal),
                List.copyOf(mismatches));
        save(report);
        if (status != Status.MATCHED) {
            log.warn("Reconciliation for {}: {} with {} mismatches", date, status, mismatches.size());
        } else {
            log.info("Reconciliation for {}: MATCHED ({} top-ups, {} paise)", date, ledger.size(), ledgerTotal);
        }
        return report;
    }

    public Optional<ReconciliationReport> latest() {
        return jdbc.sql("SELECT report::text FROM reconciliation_runs ORDER BY ran_at DESC LIMIT 1")
                .query(String.class)
                .optional()
                .map(this::fromJson);
    }

    private void save(ReconciliationReport report) {
        try {
            jdbc.sql("""
                            INSERT INTO reconciliation_runs (id, business_date, status, mismatch_count, report)
                            VALUES (:id, :date, :status, :count, CAST(:report AS jsonb))
                            """)
                    .param("id", report.runId())
                    .param("date", report.businessDate())
                    .param("status", report.status().name())
                    .param("count", report.mismatches().size())
                    .param("report", json.writeValueAsString(report))
                    .update();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ReconciliationReport fromJson(String text) {
        try {
            return json.readValue(text, ReconciliationReport.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BankStatement.Parsed read(Path file) {
        try {
            return BankStatement.read(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read bank statement " + file, e);
        }
    }
}
