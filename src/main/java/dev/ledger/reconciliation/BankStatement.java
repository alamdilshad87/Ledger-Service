package dev.ledger.reconciliation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The bank's view of money it sent into our clearing account: one credit line per top-up.
 * <pre>
 * date,reference,description,amount_paise
 * 2026-11-20,6f1c…,WALLET TOPUP,120000
 * </pre>
 * {@code reference} is the top-up's journal entry id, the way a real bank echoes a payment reference.
 */
public final class BankStatement {

    static final String HEADER = "date,reference,description,amount_paise";

    public record Line(LocalDate date, String reference, String description, long amountPaise) {
    }

    /** A line that couldn't be read, kept so the report can say which one and why. */
    public record BadLine(int lineNumber, String content, String problem) {
    }

    public record Parsed(List<Line> lines, List<BadLine> badLines) {
    }

    private BankStatement() {
    }

    public static void write(Path file, List<Line> lines) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        List<String> out = new ArrayList<>();
        out.add(HEADER);
        for (Line line : lines) {
            out.add(line.date() + "," + line.reference() + "," + line.description() + "," + line.amountPaise());
        }
        Files.write(file, out, StandardCharsets.UTF_8);
    }

    public static Parsed read(Path file) throws IOException {
        List<Line> lines = new ArrayList<>();
        List<BadLine> bad = new ArrayList<>();
        List<String> raw = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < raw.size(); i++) {
            String text = raw.get(i).strip();
            if (text.isEmpty() || (i == 0 && text.equalsIgnoreCase(HEADER))) {
                continue;
            }
            String[] cols = text.split(",", -1);
            if (cols.length != 4) {
                bad.add(new BadLine(i + 1, text, "expected 4 columns, found " + cols.length));
                continue;
            }
            try {
                lines.add(new Line(LocalDate.parse(cols[0].strip()), cols[1].strip(), cols[2].strip(),
                        Long.parseLong(cols[3].strip())));
            } catch (RuntimeException e) {
                bad.add(new BadLine(i + 1, text, e.getMessage()));
            }
        }
        return new Parsed(lines, bad);
    }
}
