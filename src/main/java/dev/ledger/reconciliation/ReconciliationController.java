package dev.ledger.reconciliation;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import dev.ledger.common.NotFoundException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin endpoints. Unauthenticated until API keys arrive in week 7. */
@RestController
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final ReconciliationProperties properties;
    private final FakeBank fakeBank;

    public ReconciliationController(ReconciliationService reconciliation, ReconciliationProperties properties,
                                    FakeBank fakeBank) {
        this.reconciliation = reconciliation;
        this.properties = properties;
        this.fakeBank = fakeBank;
    }

    @GetMapping("/v1/admin/reconciliation/latest")
    public ReconciliationReport latest() {
        return reconciliation.latest()
                .orElseThrow(() -> new NotFoundException("NO_RECONCILIATION_RUNS", "Reconciliation has not run yet"));
    }

    /** Runs reconciliation now; {@code date} defaults to yesterday in the business time zone. */
    @PostMapping("/v1/admin/reconciliation/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public ReconciliationReport run(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconciliation.run(date != null ? date : LocalDate.now(properties.zone()).minusDays(1));
    }

    /** Demo only: writes the statement the bank would send for {@code date}, optionally with one fault. */
    @PostMapping("/v1/admin/fake-bank/statements")
    @ResponseStatus(HttpStatus.CREATED)
    public FakeStatement writeFakeStatement(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "NONE") FakeBank.Fault fault) throws IOException {
        if (!properties.fakeBankEnabled()) {
            throw new NotFoundException("NOT_FOUND", "Fake bank is disabled");
        }
        var file = fakeBank.writeStatement(date, fault);
        List<BankStatement.Line> lines = BankStatement.read(file).lines();
        return new FakeStatement(file.toString(), date, fault, lines.size(),
                lines.stream().mapToLong(BankStatement.Line::amountPaise).sum());
    }

    public record FakeStatement(String file, LocalDate date, FakeBank.Fault fault, int lines, long totalPaise) {
    }
}
