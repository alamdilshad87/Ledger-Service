package dev.ledger.common;

import org.springframework.http.HttpStatus;

/** The request is well-formed but breaks a business rule (e.g. transferring to yourself). */
public class UnprocessableException extends ApiException {

    public UnprocessableException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
