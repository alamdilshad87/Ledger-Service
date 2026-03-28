package dev.ledger.account;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
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

    public long createMerchantAccount(UUID merchantId, String currency) {
        return jdbc.sql("""
                        INSERT INTO accounts (merchant_id, type, currency)
                        VALUES (:merchantId, 'MERCHANT', :currency)
                        RETURNING id
                        """)
                .param("merchantId", merchantId)
                .param("currency", currency)
                .query(Long.class)
                .single();
    }

    /** The account a merchant's payments are credited to, or empty if the merchant doesn't exist. */
    public Optional<Long> merchantAccountId(UUID merchantId) {
        return jdbc.sql("SELECT id FROM accounts WHERE merchant_id = :merchantId AND type = 'MERCHANT'")
                .param("merchantId", merchantId)
                .query(Long.class)
                .optional();
    }

    /** The single system account every top-up is funded from (seeded by migration V1). */
    public long clearingAccountId() {
        return jdbc.sql("SELECT id FROM accounts WHERE type = 'SYSTEM_CLEARING' ORDER BY id LIMIT 1")
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

    /** Plain read. The balance may be stale by the time you act on it; use {@link #lockForUpdate} for that. */
    public Optional<Account> findById(long id) {
        return jdbc.sql(SELECT_ACCOUNT)
                .param("id", id)
                .query(ACCOUNT_ROW)
                .optional();
    }

    /**
     * Reads the account and holds its row lock until the transaction ends, so its balance can't change
     * underneath you. Must run inside a transaction; callers locking several accounts must do so in
     * ascending id order to avoid deadlocks.
     */
    public Optional<Account> lockForUpdate(long id) {
        return jdbc.sql(SELECT_ACCOUNT + " FOR UPDATE")
                .param("id", id)
                .query(ACCOUNT_ROW)
                .optional();
    }

    private static final String SELECT_ACCOUNT =
            "SELECT id, owner_id, type, currency, balance, created_at FROM accounts WHERE id = :id";

    private static final RowMapper<Account> ACCOUNT_ROW = (rs, n) -> new Account(
            rs.getLong("id"),
            rs.getObject("owner_id", UUID.class),
            AccountType.valueOf(rs.getString("type")),
            rs.getString("currency").trim(),
            rs.getLong("balance"),
            rs.getObject("created_at", OffsetDateTime.class));
}
