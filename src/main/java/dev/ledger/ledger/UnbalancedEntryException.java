package dev.ledger.ledger;

/** A journal entry that would break double-entry rules. Always a bug in the caller, never user input. */
public class UnbalancedEntryException extends IllegalArgumentException {

    public UnbalancedEntryException(String message) {
        super(message);
    }
}
