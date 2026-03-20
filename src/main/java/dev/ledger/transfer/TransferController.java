package dev.ledger.transfer;

import dev.ledger.common.Amounts;
import dev.ledger.common.IdempotencyKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transfers")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferService.TransferResult create(
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return transfers.transfer(request.fromAccountId(), request.toAccountId(), request.amount(),
                request.note(), IdempotencyKeys.require(idempotencyKey));
    }

    /** {@code amount} is in paise: 50000 = ₹500. */
    public record TransferRequest(
            @NotNull Long fromAccountId,
            @NotNull Long toAccountId,
            @NotNull @Positive @Max(Amounts.MAX_PAISE) Long amount,
            @Size(max = 140) String note) {
    }
}
