package dev.ledger.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import dev.ledger.common.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    static final String KEY_PREFIX = "lk_";

    private final JdbcClient jdbc;

    public ApiKeyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a key and returns it in full. This is the only time the plain key exists outside the caller. */
    public CreatedKey create(String name, ApiClient.Role role) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String key = KEY_PREFIX + HexFormat.of().formatHex(secret);
        UUID id = UUID.randomUUID();
        insert(id, name, key, role);
        return new CreatedKey(id, name, role, displayPrefix(key), key);
    }

    /** Registers a key the operator chose (e.g. from an environment variable); does nothing if it exists. */
    public void ensure(String name, String key, ApiClient.Role role) {
        jdbc.sql("""
                        INSERT INTO api_keys (id, name, key_prefix, key_hash, role)
                        VALUES (:id, :name, :prefix, :hash, :role)
                        ON CONFLICT (key_hash) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("name", name)
                .param("prefix", displayPrefix(key))
                .param("hash", hash(key))
                .param("role", role.name())
                .update();
    }

    public Optional<ApiClient> authenticate(String key) {
        if (key == null || key.length() < 16 || key.length() > 200) {
            return Optional.empty();
        }
        // Looked up by hash: an index lookup on a value an attacker can't choose to collide.
        return jdbc.sql("SELECT id, name, role FROM api_keys WHERE key_hash = :hash AND active")
                .param("hash", hash(key))
                .query((rs, n) -> new ApiClient(rs.getObject("id", UUID.class), rs.getString("name"),
                        ApiClient.Role.valueOf(rs.getString("role"))))
                .optional();
    }

    public void revoke(UUID id) {
        int updated = jdbc.sql("UPDATE api_keys SET active = false, revoked_at = now() WHERE id = :id AND active")
                .param("id", id).update();
        if (updated == 0) {
            throw new NotFoundException("API_KEY_NOT_FOUND", "No active API key " + id);
        }
    }

    private void insert(UUID id, String name, String key, ApiClient.Role role) {
        jdbc.sql("INSERT INTO api_keys (id, name, key_prefix, key_hash, role) VALUES (:id, :name, :prefix, :hash, :role)")
                .param("id", id)
                .param("name", name)
                .param("prefix", displayPrefix(key))
                .param("hash", hash(key))
                .param("role", role.name())
                .update();
    }

    private static String displayPrefix(String key) {
        return key.substring(0, Math.min(key.length(), 11));
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record CreatedKey(UUID id, String name, ApiClient.Role role, String prefix, String key) {
    }
}
