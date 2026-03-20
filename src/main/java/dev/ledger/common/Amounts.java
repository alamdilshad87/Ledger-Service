package dev.ledger.common;

public final class Amounts {

    /**
     * Largest single amount accepted, in paise (₹1 crore). Keeps every sum of a few postings
     * far away from {@code Long} overflow and stops typos like an extra six zeros.
     */
    public static final long MAX_PAISE = 1_000_000_000L;

    private Amounts() {
    }
}
