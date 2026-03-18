package dev.ledger.account;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long createWallet(UUID ownerId, String currency) {
        return jdbc.sql("""
                        INSERT INTO accounts (owner_id, type, currency)
                        VALUES (:ownerId, 'USER_WALLET', :currency)
                        RETURNING id
                        """)
                .param("ownerId", ownerId)
                .param("currency", currency)
                .query(Long.class)
                .single();
    }

    /**
     * Adds {@code delta} paise to the cached balance and returns the new balance,
     * or empty if the account doesn't exist. Only {@code LedgerService} should call this.
     */
    public Optional<Long> applyDelta(long id, long delta) {
        return jdbc.sql("""
                        UPDATE accounts SET balance = balance + :delta, version = version + 1
                        WHERE id = :id
                        RETURNING balance
                        """)
                .param("id", id)
                .param("delta", delta)
                .query(Long.class)
                .optional();
    }

    public Optional<Account> findById(long id) {
        return jdbc.sql("""
                        SELECT id, owner_id, type, currency, balance, created_at
                        FROM accounts WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new Account(
                        rs.getLong("id"),
                        rs.getObject("owner_id", UUID.class),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getString("currency").trim(),
                        rs.getLong("balance"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }
}
