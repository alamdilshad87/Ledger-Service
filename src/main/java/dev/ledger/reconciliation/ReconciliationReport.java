package dev.ledger.reconciliation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReconciliationReport(
        UUID runId,
        LocalDate businessDate,
        Status status,
        OffsetDateTime ranAt,
        String statementFile,
        Summary summary,
        List<Mismatch> mismatches) {

    public enum Status { MATCHED, MISMATCHED, STATEMENT_MISSING }

    public enum MismatchType {
        /** The sum of every posting isn't zero: money was created or destroyed. */
        LEDGER_UNBALANCED,
        /** An account's cached balance differs from the sum of its postings. */
        BALANCE_DRIFT,
        /** We recorded a top-up the bank doesn't know about. */
        MISSING_IN_BANK,
        /** The bank sent money we have no top-up for. */
        MISSING_IN_LEDGER,
        /** Both sides know the top-up but disagree on the amount. */
        AMOUNT_MISMATCH,
        /** The same bank reference appears more than once. */
        DUPLICATE_BANK_LINE,
        /** A statement line couldn't be parsed. */
        MALFORMED_BANK_LINE
    }

    /** {@code expected} is the ledger's value, {@code actual} the other side's (bank, or cached balance). */
    public record Mismatch(MismatchType type, String reference, Long expected, Long actual, String detail) {
    }

    public record Summary(
            long ledgerPostingsTotal,
            long accountsChecked,
            long ledgerTopUpCount,
            long ledgerTopUpTotal,
            long bankLineCount,
            long bankTotal) {
    }
}
