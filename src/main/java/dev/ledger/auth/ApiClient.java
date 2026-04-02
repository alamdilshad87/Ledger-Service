package dev.ledger.auth;

import java.util.UUID;

/** The authenticated caller of the current request. */
public record ApiClient(UUID id, String name, Role role) {

    public enum Role { CLIENT, ADMIN }

    /** Request attribute holding the {@link ApiClient}. */
    public static final String ATTRIBUTE = "ledger.apiClient";
}
