package dev.ledger.common;

import java.sql.SQLTransientConnectionException;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Recognises "no database connection available in time": the service is busy, not broken. */
public final class Overload {

    public static final String CODE = "SERVICE_BUSY";
    public static final String MESSAGE = "The service is busy; retry shortly with the same Idempotency-Key";
    public static final String RETRY_AFTER_SECONDS = "1";

    private Overload() {
    }

    public static boolean isPoolExhausted(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException
                    || t instanceof CannotGetJdbcConnectionException
                    || t instanceof CannotCreateTransactionException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
