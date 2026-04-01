package dev.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import dev.ledger.reconciliation.ReconciliationReport.Mismatch;
import dev.ledger.reconciliation.ReconciliationReport.MismatchType;
import dev.ledger.reconciliation.ReconciliationReport.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class ReconciliationTest extends ApiTestSupport {

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    FakeBank bank;

    @Autowired
    ReconciliationProperties properties;

    private LocalDate today() {
        return LocalDate.now(properties.zone());
    }

    /** Tops up a fresh wallet and returns the top-up's journal entry id (the bank reference). */
    private String topUp(long wallet, long paise) {
        return (String) post("/v1/topups", Map.of("accountId", wallet, "amount", paise)).getBody().get("entryId");
    }

    private static List<Mismatch> ofType(ReconciliationReport report, MismatchType type) {
        return report.mismatches().stream().filter(m -> m.type() == type).toList();
    }

    @Test
    void honestStatementMatches() throws Exception {
        long wallet = newWallet();
        topUp(wallet, 120_000);
        topUp(wallet, 30_000);
        bank.writeStatement(today(), FakeBank.Fault.NONE);

        ReconciliationReport report = reconciliation.run(today());

        assertThat(report.status()).as("mismatches: %s", report.mismatches()).isEqualTo(Status.MATCHED);
        assertThat(report.summary().ledgerPostingsTotal()).isZero();
        assertThat(report.summary().bankTotal()).isEqualTo(report.summary().ledgerTopUpTotal());
        assertThat(report.summary().ledgerTopUpCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void topUpTheBankNeverSentIsMissingInBank() throws Exception {
        String reference = topUp(newWallet(), 55_500);
        List<BankStatement.Line> lines = new ArrayList<>(bank.honestStatement(today()));
        lines.removeIf(l -> l.reference().equals(reference));
        bank.writeStatement(today(), lines);

        ReconciliationReport report = reconciliation.run(today());

        assertThat(report.status()).isEqualTo(Status.MISMATCHED);
        assertThat(ofType(report, MismatchType.MISSING_IN_BANK)).singleElement()
                .satisfies(m -> assertThat(m).extracting(Mismatch::reference, Mismatch::expected).containsExactly(reference, 55_500L));
    }

    @Test
    void bankCreditingLessIsAnAmountMismatch() throws Exception {
        String reference = topUp(newWallet(), 10_000);
        List<BankStatement.Line> lines = new ArrayList<>(bank.honestStatement(today()));
        lines.replaceAll(l -> l.reference().equals(reference)
                ? new BankStatement.Line(l.date(), l.reference(), l.description(), 9_900) : l);
        bank.writeStatement(today(), lines);

        ReconciliationReport report = reconciliation.run(today());

        assertThat(ofType(report, MismatchType.AMOUNT_MISMATCH)).singleElement()
                .satisfies(m -> assertThat(m).extracting(Mismatch::reference, Mismatch::expected, Mismatch::actual)
                        .containsExactly(reference, 10_000L, 9_900L));
    }

    @Test
    void unknownBankCreditAndDuplicatesAndBadLinesAreFlagged() throws Exception {
        topUp(newWallet(), 5_000);
        List<BankStatement.Line> lines = new ArrayList<>(bank.honestStatement(today()));
        String stranger = UUID.randomUUID().toString();
        lines.add(new BankStatement.Line(today(), stranger, "UNKNOWN", 1_234));
        lines.add(lines.get(0));
        var file = bank.writeStatement(today(), lines);
        Files.writeString(file, Files.readString(file) + "not,a,valid,amount\n");

        ReconciliationReport report = reconciliation.run(today());

        assertThat(ofType(report, MismatchType.MISSING_IN_LEDGER)).extracting(Mismatch::reference).containsExactly(stranger);
        assertThat(ofType(report, MismatchType.DUPLICATE_BANK_LINE)).hasSize(1);
        assertThat(ofType(report, MismatchType.MALFORMED_BANK_LINE)).hasSize(1);
    }

    @Test
    void manuallyCorruptedBalanceIsFlagged() throws Exception {
        long wallet = newWallet();
        topUp(wallet, 20_000);
        bank.writeStatement(today(), FakeBank.Fault.NONE);
        jdbc.sql("UPDATE accounts SET balance = balance + 100 WHERE id = :id").param("id", wallet).update();
        try {
            ReconciliationReport report = reconciliation.run(today());

            assertThat(report.status()).isEqualTo(Status.MISMATCHED);
            assertThat(ofType(report, MismatchType.BALANCE_DRIFT)).singleElement()
                    .satisfies(m -> assertThat(m).extracting(Mismatch::reference, Mismatch::expected, Mismatch::actual)
                            .containsExactly("account:" + wallet, 20_000L, 20_100L));
        } finally {
            // Other tests assert every balance matches its postings; undo the damage.
            jdbc.sql("UPDATE accounts SET balance = balance - 100 WHERE id = :id").param("id", wallet).update();
        }
    }

    @Test
    void missingStatementIsReportedButInternalChecksStillRun() {
        ReconciliationReport report = reconciliation.run(LocalDate.of(2020, 1, 1));

        assertThat(report.status()).isEqualTo(Status.STATEMENT_MISSING);
        assertThat(report.summary().accountsChecked()).isPositive();
        assertThat(report.mismatches()).isEmpty();
    }

    @Test
    void endpointsRunReconciliationAndShowTheLatestReport() {
        topUp(newWallet(), 7_000);
        String day = today().toString();

        var fake = post("/v1/admin/fake-bank/statements?date=" + day + "&fault=DROP_LAST", null);
        var run = post("/v1/admin/reconciliation/runs?date=" + day, null);
        var latest = get("/v1/admin/reconciliation/latest");

        assertThat(fake.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(run.getBody()).containsEntry("status", "MISMATCHED");
        assertThat(latest.getBody()).containsEntry("runId", run.getBody().get("runId"))
                .containsEntry("businessDate", day);
        assertThat(jdbc.sql("SELECT mismatch_count FROM reconciliation_runs WHERE id = CAST(:id AS uuid)")
                .param("id", run.getBody().get("runId")).query(Integer.class).single()).isEqualTo(1);
    }
}
