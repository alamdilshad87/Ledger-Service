package dev.ledger.statement;

import java.util.List;

import dev.ledger.account.AccountRepository;
import dev.ledger.common.BadRequestException;
import dev.ledger.common.NotFoundException;
import dev.ledger.statement.StatementRepository.StatementLine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatementController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final AccountRepository accounts;
    private final StatementRepository statements;

    public StatementController(AccountRepository accounts, StatementRepository statements) {
        this.accounts = accounts;
        this.statements = statements;
    }

    /**
     * First page: no cursor. Next page: pass the previous response's {@code nextCursor}.
     * {@code nextCursor} is null on the last page.
     */
    @GetMapping("/v1/accounts/{id}/statement")
    public Statement statement(
            @PathVariable long id,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("INVALID_LIMIT", "limit must be between 1 and " + MAX_LIMIT);
        }
        if (cursor != null && cursor < 1) {
            throw new BadRequestException("INVALID_CURSOR", "cursor must be a positive posting id");
        }
        if (accounts.findById(id).isEmpty()) {
            throw new NotFoundException("ACCOUNT_NOT_FOUND", "Account " + id + " not found");
        }

        // Fetch one extra row to learn whether another page exists without a COUNT query.
        List<StatementLine> rows = statements.page(id, cursor == null ? Long.MAX_VALUE : cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<StatementLine> items = hasMore ? rows.subList(0, limit) : rows;
        Long nextCursor = hasMore ? items.get(items.size() - 1).postingId() : null;
        return new Statement(id, items, nextCursor);
    }

    public record Statement(long accountId, List<StatementLine> items, Long nextCursor) {
    }
}
