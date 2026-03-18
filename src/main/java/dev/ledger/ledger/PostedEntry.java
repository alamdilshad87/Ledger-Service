package dev.ledger.ledger;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Result of posting an entry: its id, commit time and each touched account's balance afterwards. */
public record PostedEntry(UUID id, OffsetDateTime createdAt, Map<Long, Long> balancesAfter) {

    public long balanceOf(long accountId) {
        Long balance = balancesAfter.get(accountId);
        if (balance == null) {
            throw new IllegalArgumentException("Account " + accountId + " was not part of entry " + id);
        }
        return balance;
    }
}
