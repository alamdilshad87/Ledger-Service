package dev.ledger.payment;

import java.util.UUID;

import dev.ledger.common.Amounts;
import dev.ledger.common.IdempotencyKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundController {

    private final RefundService refunds;

    public RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    /** Body {@code {}} (or no amount) refunds everything still refundable; {@code amount} in paise refunds part. */
    @PostMapping("/v1/payments/{paymentId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundService.RefundResult create(
            @PathVariable UUID paymentId,
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {
        return refunds.refund(paymentId, request.amount(), request.note(), IdempotencyKeys.require(idempotencyKey));
    }

    public record RefundRequest(@Positive @Max(Amounts.MAX_PAISE) Long amount, @Size(max = 140) String note) {
    }
}
