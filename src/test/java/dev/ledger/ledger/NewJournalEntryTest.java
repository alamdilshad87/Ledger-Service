package dev.ledger.ledger;

import static dev.ledger.ledger.Posting.credit;
import static dev.ledger.ledger.Posting.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class NewJournalEntryTest {

    @Test
    void balancedTwoLegEntryIsAccepted() {
        NewJournalEntry entry = NewJournalEntry.of(EntryType.TRANSFER, "k", null,
                List.of(debit(1, 50_000), credit(2, 50_000)));

        assertThat(entry.postings()).hasSize(2);
        assertThat(entry.postings().stream().mapToLong(Posting::amount).sum()).isZero();
    }

    @Test
    void balancedMultiLegEntryIsAccepted() {
        // e.g. a payment split between merchant and a platform fee account
        NewJournalEntry entry = NewJournalEntry.of(EntryType.PAYMENT, "k", null,
                List.of(debit(1, 10_000), credit(2, 9_800), credit(3, 200)));

        assertThat(entry.postings()).hasSize(3);
    }

    @Test
    void entryThatDoesNotSumToZeroIsRejected() {
        assertThatThrownBy(() -> NewJournalEntry.of(EntryType.TRANSFER, "k", null,
                List.of(debit(1, 50_000), credit(2, 49_999))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("sum to -1");
    }

    @Test
    void singlePostingIsRejected() {
        assertThatThrownBy(() -> NewJournalEntry.of(EntryType.TOPUP, "k", null, List.of(credit(1, 100))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void zeroAmountPostingIsRejected() {
        assertThatThrownBy(() -> NewJournalEntry.of(EntryType.TRANSFER, "k", null,
                List.of(debit(1, 0), credit(2, 0))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("zero amount");
    }

    @Test
    void sameAccountTwiceIsRejected() {
        assertThatThrownBy(() -> NewJournalEntry.of(EntryType.TRANSFER, "k", null,
                List.of(debit(1, 100), credit(1, 100))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("appears twice");
    }

    @Test
    void overflowingAmountsAreRejectedInsteadOfWrappingToZero() {
        // Long.MAX_VALUE + 1 wraps to Long.MIN_VALUE; without addExact these three could "sum" to 0.
        assertThatThrownBy(() -> NewJournalEntry.of(EntryType.TRANSFER, "k", null,
                List.of(credit(1, Long.MAX_VALUE), credit(2, 1), new Posting(3, Long.MIN_VALUE))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("overflow");
    }

    @Test
    void postingsListIsDefensivelyCopied() {
        var postings = new java.util.ArrayList<>(List.of(debit(1, 100), credit(2, 100)));
        NewJournalEntry entry = NewJournalEntry.of(EntryType.TRANSFER, "k", null, postings);

        postings.add(credit(3, 1));

        assertThat(entry.postings()).hasSize(2);
    }
}
