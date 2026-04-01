package dev.ledger.reconciliation;

import java.nio.file.Path;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param statementDir    where bank statements land, one file per day: {@code bank-statement-YYYY-MM-DD.csv}
 * @param zone            the business day's time zone (a "day" of top-ups is midnight to midnight here)
 * @param fakeBankEnabled expose the endpoint that writes a fake statement from the ledger (demo only)
 */
@ConfigurationProperties("ledger.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("bank-statements") Path statementDir,
        @DefaultValue("Asia/Kolkata") ZoneId zone,
        @DefaultValue("true") boolean fakeBankEnabled) {

    public Path statementFor(java.time.LocalDate date) {
        return statementDir.resolve("bank-statement-" + date + ".csv");
    }
}
