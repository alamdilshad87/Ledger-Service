package dev.ledger.ledger;

/** One leg of a journal entry. Amount is in paise: negative debits the account, positive credits it. */
public record Posting(long accountId, long amount) {

    public static Posting debit(long accountId, long amount) {
        return new Posting(accountId, -amount);
    }

    public static Posting credit(long accountId, long amount) {
        return new Posting(accountId, amount);
    }
}
