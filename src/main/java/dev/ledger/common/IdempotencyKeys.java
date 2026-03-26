package dev.ledger.common;

/**
 * Every money-moving POST must carry an {@code Idempotency-Key} header.
 * {@code IdempotencyFilter} enforces it and handles replays; controllers read it to record on the entry.
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
