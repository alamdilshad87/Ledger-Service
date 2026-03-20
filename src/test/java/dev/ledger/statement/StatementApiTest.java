package dev.ledger.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class StatementApiTest extends ApiTestSupport {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    @Test
    void statementListsPostingsNewestFirstWithSignedAmounts() {
        long asha = newWalletWith(100_000);
        long ravi = newWallet();
        post("/v1/transfers", Map.of("fromAccountId", asha, "toAccountId", ravi, "amount", 30_000, "note", "Rent"));

        var response = get("/v1/accounts/" + asha + "/statement");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var lines = items(response.getBody());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("entryType", "TRANSFER").containsEntry("amount", -30_000)
                .containsEntry("note", "Rent");
        assertThat(lines.get(1)).containsEntry("entryType", "TOPUP").containsEntry("amount", 100_000);
        assertThat(response.getBody().get("nextCursor")).isNull();
    }

    @Test
    void cursorWalksEveryPostingExactlyOnce() {
        long asha = newWallet();
        for (int i = 1; i <= 7; i++) {
            post("/v1/topups", Map.of("accountId", asha, "amount", i * 100));
        }

        List<Long> amounts = new ArrayList<>();
        String path = "/v1/accounts/" + asha + "/statement?limit=3";
        int pages = 0;
        Object cursor;
        do {
            var body = get(path).getBody();
            items(body).forEach(line -> amounts.add(asLong(line.get("amount"))));
            cursor = body.get("nextCursor");
            path = "/v1/accounts/" + asha + "/statement?limit=3&cursor=" + cursor;
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(amounts).containsExactly(700L, 600L, 500L, 400L, 300L, 200L, 100L);
    }

    @Test
    void emptyWalletHasEmptyStatement() {
        long wallet = newWallet();

        var body = get("/v1/accounts/" + wallet + "/statement").getBody();

        assertThat(items(body)).isEmpty();
        assertThat(body.get("nextCursor")).isNull();
    }

    @Test
    void unknownAccountIs404() {
        assertThat(get("/v1/accounts/999999999/statement").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void outOfRangeLimitIs400() {
        long wallet = newWallet();

        assertThat(get("/v1/accounts/" + wallet + "/statement?limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/v1/accounts/" + wallet + "/statement?limit=101").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
