package dev.ledger.ledger;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A journal entry about to be posted. Construction enforces the double-entry rules,
 * so an unbalanced entry can't even be built:
 * <ul>
 *   <li>at least two postings</li>
 *   <li>no zero-amount postings</li>
 *   <li>each account appears at most once</li>
 *   <li>amounts sum to exactly zero (without overflowing)</li>
 * </ul>
 */
public record NewJournalEntry(
        UUID id,
        EntryType type,
        String idempotencyKey,
        UUID referenceId,
        String note,
        List<Posting> postings) {

    public NewJournalEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        postings = List.copyOf(postings);

        if (postings.size() < 2) {
            throw new UnbalancedEntryException("An entry needs at least two postings, got " + postings.size());
        }
        Set<Long> accounts = new HashSet<>();
        long sum = 0;
        for (Posting posting : postings) {
            if (posting.amount() == 0) {
                throw new UnbalancedEntryException("Posting to account " + posting.accountId() + " has a zero amount");
            }
            if (!accounts.add(posting.accountId())) {
                throw new UnbalancedEntryException("Account " + posting.accountId() + " appears twice in one entry");
            }
            try {
                sum = Math.addExact(sum, posting.amount());
            } catch (ArithmeticException e) {
                throw new UnbalancedEntryException("Posting amounts overflow");
            }
        }
        if (sum != 0) {
            throw new UnbalancedEntryException("Postings sum to " + sum + " paise; they must sum to 0");
        }
    }

    public static NewJournalEntry of(EntryType type, String idempotencyKey, String note, List<Posting> postings) {
        return new NewJournalEntry(UUID.randomUUID(), type, idempotencyKey, null, note, postings);
    }

    /** An entry that points back at an earlier one, e.g. a refund of a payment. */
    public static NewJournalEntry referencing(UUID referenceId, EntryType type, String idempotencyKey, String note,
                                              List<Posting> postings) {
        return new NewJournalEntry(UUID.randomUUID(), type, idempotencyKey,
                Objects.requireNonNull(referenceId, "referenceId"), note, postings);
    }
}
