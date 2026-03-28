package dev.ledger.payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import dev.ledger.common.Amounts;
import dev.ledger.common.IdempotencyKeys;
import dev.ledger.common.NotFoundException;
import dev.ledger.payment.PaymentRepository.RefundLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository payments;

    public PaymentController(PaymentService paymentService, PaymentRepository payments) {
        this.paymentService = paymentService;
        this.payments = payments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentService.PaymentResult create(
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        return paymentService.pay(request.fromAccountId(), request.merchantId(), request.amount(), request.note(),
                IdempotencyKeys.require(idempotencyKey));
    }

    @GetMapping("/{id}")
    public PaymentView get(@PathVariable UUID id) {
        Payment payment = payments.findById(id)
                .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "Payment " + id + " not found"));
        return new PaymentView(payment.id(), payment.status(), payment.walletAccountId(), payment.merchantId(),
                payment.amount(), payment.refundedAmount(), payment.createdAt(), payments.refunds(payment));
    }

    /** {@code amount} is in paise: 50000 = ₹500. */
    public record PaymentRequest(
            @NotNull Long fromAccountId,
            @NotNull UUID merchantId,
            @NotNull @Positive @Max(Amounts.MAX_PAISE) Long amount,
            @Size(max = 140) String note) {
    }

    public record PaymentView(UUID paymentId, String status, long walletAccountId, UUID merchantId, long amount,
                              long refundedAmount, OffsetDateTime createdAt, List<RefundLine> refunds) {
    }
}
