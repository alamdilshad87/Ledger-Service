package dev.ledger.reconciliation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Stands in for the bank in demos and tests: writes the statement the bank <em>would</em> send for a day,
 * optionally with one deliberate fault so reconciliation has something to catch. A real deployment reads
 * the statement the bank actually delivers and never uses this.
 */
@Component
public class FakeBank {

    public enum Fault {
        NONE,
        /** The bank never received the last top-up of the day. */
        DROP_LAST,
        /** The bank credited ₹1 less on the last top-up. */
        SHORT_LAST,
        /** The bank shows a credit we have no record of. */
        ADD_UNKNOWN
    }

    private final LedgerTopUps topUps;
    private final ReconciliationProperties properties;

    public FakeBank(LedgerTopUps topUps, ReconciliationProperties properties) {
        this.topUps = topUps;
        this.properties = properties;
    }

    public List<BankStatement.Line> honestStatement(LocalDate date) {
        List<BankStatement.Line> lines = new ArrayList<>();
        topUps.on(date).forEach((reference, amount) ->
                lines.add(new BankStatement.Line(date, reference, "WALLET TOPUP", amount)));
        return lines;
    }

    public Path writeStatement(LocalDate date, Fault fault) throws IOException {
        return writeStatement(date, applyFault(date, honestStatement(date), fault));
    }

    public Path writeStatement(LocalDate date, List<BankStatement.Line> lines) throws IOException {
        Path file = properties.statementFor(date);
        BankStatement.write(file, lines);
        return file;
    }

    private static List<BankStatement.Line> applyFault(LocalDate date, List<BankStatement.Line> lines, Fault fault) {
        List<BankStatement.Line> out = new ArrayList<>(lines);
        switch (fault) {
            case NONE -> { }
            case DROP_LAST -> {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
            }
            case SHORT_LAST -> {
                if (!out.isEmpty()) {
                    BankStatement.Line last = out.remove(out.size() - 1);
                    out.add(new BankStatement.Line(last.date(), last.reference(), last.description(), last.amountPaise() - 100));
                }
            }
            case ADD_UNKNOWN -> out.add(new BankStatement.Line(date, UUID.randomUUID().toString(), "UNKNOWN CREDIT", 77_700));
        }
        return out;
    }
}
