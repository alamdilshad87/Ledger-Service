package dev.ledger.topup;

import dev.ledger.common.Amounts;
import dev.ledger.common.IdempotencyKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/topups")
public class TopUpController {

    private final TopUpService topUps;

    public TopUpController(TopUpService topUps) {
        this.topUps = topUps;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TopUpService.TopUpResult create(
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @Valid @RequestBody TopUpRequest request) {
        return topUps.topUp(request.accountId(), request.amount(), IdempotencyKeys.require(idempotencyKey));
    }

    /** {@code amount} is in paise: 50000 = ₹500. */
    public record TopUpRequest(
            @NotNull Long accountId,
            @NotNull @Positive @Max(Amounts.MAX_PAISE) Long amount) {
    }
}
