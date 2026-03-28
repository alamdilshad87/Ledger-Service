package dev.ledger.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Postgres side of idempotency: the source of truth. */
@Repository
public class IdempotencyStore {

    public enum Status { IN_PROGRESS, COMPLETED }

    public record KeyRecord(String requestHash, Status status, Integer responseCode, String responseBody) {
    }

    private final JdbcClient jdbc;

    public IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key for this request and returns a claim token, or empty if someone else holds it.
     * <p>
     * A new key is inserted as IN_PROGRESS. An existing IN_PROGRESS key with the same request hash whose
     * lease has expired is taken over (its owner died before committing). Atomic either way: under
     * concurrency exactly one caller gets a token. Runs in its own transaction so the claim is visible
     * to other requests immediately.
     */
    public Optional<UUID> tryClaim(String key, String requestHash, Duration lease) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (key, request_hash, status, claim_token)
                        VALUES (:key, :hash, 'IN_PROGRESS', :token)
                        ON CONFLICT (key) DO UPDATE
                            SET claim_token = EXCLUDED.claim_token, locked_at = now()
                            WHERE idempotency_keys.status = 'IN_PROGRESS'
                              AND idempotency_keys.request_hash = EXCLUDED.request_hash
                              AND idempotency_keys.locked_at < now() - make_interval(secs => CAST(:lease AS double precision))
                        RETURNING claim_token
                        """)
                .param("key", key)
                .param("hash", requestHash)
                .param("token", UUID.randomUUID())
                .param("lease", lease.toMillis() / 1000.0)
                .query(UUID.class)
                .optional();
    }

    public Optional<KeyRecord> find(String key) {
        return jdbc.sql("SELECT request_hash, status, response_code, response_body FROM idempotency_keys WHERE key = :key")
                .param("key", key)
                .query((rs, n) -> new KeyRecord(
                        rs.getString("request_hash"),
                        Status.valueOf(rs.getString("status")),
                        (Integer) rs.getObject("response_code"),
                        rs.getString("response_body")))
                .optional();
    }

    /**
     * Records the final response. Returns false if this claim was taken over in the meantime; the caller
     * must then roll back its work. Call it inside the business transaction so the money movement and
     * the COMPLETED status commit together.
     */
    public boolean complete(String key, UUID claimToken, int responseCode, String responseBody) {
        return jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', response_code = :code, response_body = :body, completed_at = now()
                        WHERE key = :key AND claim_token = :token AND status = 'IN_PROGRESS'
                        """)
                .param("key", key)
                .param("token", claimToken)
                .param("code", responseCode)
                .param("body", responseBody)
                .update() == 1;
    }

    /**
     * Forgets keys created more than {@code ttl} ago and returns how many were removed. An IN_PROGRESS
     * row that old never committed any money (see V3__idempotency_keys.sql), so it's safe to drop too.
     */
    public int deleteOlderThan(Duration ttl) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < now() - make_interval(secs => CAST(:ttl AS double precision))")
                .param("ttl", ttl.toMillis() / 1000.0)
                .update();
    }

    /** Gives the key back so the client can retry, e.g. after a validation error or a server error. */
    public void release(String key, UUID claimToken) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE key = :key AND claim_token = :token AND status = 'IN_PROGRESS'")
                .param("key", key)
                .param("token", claimToken)
                .update();
    }
}
