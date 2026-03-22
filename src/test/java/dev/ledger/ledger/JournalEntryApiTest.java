package dev.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;

class JournalEntryApiTest extends ApiTestSupport {

    @SuppressWarnings("unchecked")
    @Test
    void entryShowsBothLegsOfATransfer() {
        long asha = newWalletWith(50_000);
        long ravi = newWallet();
        var transfer = post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 20_000));
        String entryId = (String) transfer.getBody().get("entryId");

        var response = get("/v1/journal-entries/" + entryId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("id", entryId).containsEntry("type", "TRANSFER");
        var postings = (List<Map<String, Object>>) response.getBody().get("postings");
        assertThat(postings).hasSize(2);
        assertThat(postings).anySatisfy(p -> {
            assertThat(asLong(p.get("accountId"))).isEqualTo(asha);
            assertThat(asLong(p.get("amount"))).isEqualTo(-20_000);
        });
        assertThat(postings).anySatisfy(p -> {
            assertThat(asLong(p.get("accountId"))).isEqualTo(ravi);
            assertThat(asLong(p.get("amount"))).isEqualTo(20_000);
        });
    }

    @Test
    void unknownEntryIs404() {
        var response = get("/v1/journal-entries/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "ENTRY_NOT_FOUND");
    }

    @Test
    void malformedIdIs400() {
        assertThat(get("/v1/journal-entries/not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void postingsAndEntriesCannotBeUpdatedOrDeleted() {
        newWalletWith(1_000);

        assertThatThrownBy(() -> jdbc.sql("UPDATE postings SET amount = amount * 2").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM postings").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_entries SET note = 'edited'").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
