package dev.ledger.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The first admin key has to come from somewhere: if {@code LEDGER_ADMIN_API_KEY} is set, it's registered
 * at startup. Use it to create real keys through {@code POST /v1/admin/api-keys}, then unset it.
 */
@Component
public class BootstrapAdminKey implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminKey.class);

    private final ApiKeyService keys;
    private final String bootstrapKey;

    public BootstrapAdminKey(ApiKeyService keys, @Value("${ledger.auth.bootstrap-admin-key:}") String bootstrapKey) {
        this.keys = keys;
        this.bootstrapKey = bootstrapKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapKey.isBlank()) {
            return;
        }
        if (bootstrapKey.length() < 32) {
            throw new IllegalStateException("ledger.auth.bootstrap-admin-key must be at least 32 characters");
        }
        // Generous limits: this key also drives local load tests.
        keys.ensure("bootstrap-admin", bootstrapKey, ApiClient.Role.ADMIN, 2_000, 5_000);
        log.info("Bootstrap admin API key registered");
    }
}
