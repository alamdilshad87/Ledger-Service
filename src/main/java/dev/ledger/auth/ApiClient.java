package dev.ledger.auth;

import java.util.UUID;

/** The authenticated caller of the current request, with its rate limit. */
public record ApiClient(UUID id, String name, Role role, int ratePerSecond, int burst) {

    public enum Role { CLIENT, ADMIN }

    /** Request attribute holding the {@link ApiClient}. */
    public static final String ATTRIBUTE = "ledger.apiClient";
}
