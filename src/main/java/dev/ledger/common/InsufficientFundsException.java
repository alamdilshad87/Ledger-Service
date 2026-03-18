package dev.ledger.common;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException() {
        super(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_FUNDS", "Not enough balance for this transaction");
    }
}
