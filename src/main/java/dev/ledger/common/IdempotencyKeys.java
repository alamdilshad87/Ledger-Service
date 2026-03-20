package dev.ledger.common;

/**
 * Every money-moving POST must carry an {@code Idempotency-Key} header.
 * For now the key is only validated and stored on the journal entry; replay protection lands in week 4.
 */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    private static final int MAX_LENGTH = 255;

    private IdempotencyKeys() {
    }

    public static String require(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("MISSING_IDEMPOTENCY_KEY", HEADER + " header is required");
        }
        if (key.length() > MAX_LENGTH) {
            throw new BadRequestException("INVALID_IDEMPOTENCY_KEY",
                    HEADER + " must be at most " + MAX_LENGTH + " characters");
        }
        return key;
    }
}
