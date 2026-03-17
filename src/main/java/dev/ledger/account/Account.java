package dev.ledger.account;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Account(
        long id,
        UUID ownerId,
        AccountType type,
        String currency,
        long balance,
        OffsetDateTime createdAt) {
}
