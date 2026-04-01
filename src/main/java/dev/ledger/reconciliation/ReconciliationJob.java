package dev.ledger.reconciliation;

import java.time.LocalDate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every night at 02:30 India time, reconciles the previous day once the bank's statement is in. */
@Component
public class ReconciliationJob {

    private final ReconciliationService reconciliation;
    private final ReconciliationProperties properties;

    public ReconciliationJob(ReconciliationService reconciliation, ReconciliationProperties properties) {
        this.reconciliation = reconciliation;
        this.properties = properties;
    }

    @Scheduled(cron = "${ledger.reconciliation.cron:0 30 2 * * *}", zone = "${ledger.reconciliation.zone:Asia/Kolkata}")
    public void reconcileYesterday() {
        reconciliation.run(LocalDate.now(properties.zone()).minusDays(1));
    }
}
