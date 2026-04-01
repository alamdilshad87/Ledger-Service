package dev.ledger.common;

import java.util.UUID;

import org.slf4j.MDC;

/** The current request's trace id, kept in the logging MDC so every log line carries it. */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    private RequestIds() {
    }

    /** The trace id of the request being handled, or a fresh one outside a request (e.g. a scheduled job). */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : UUID.randomUUID().toString();
    }
}
