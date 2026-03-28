package dev.ledger.payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private static final String SELECT = """
            SELECT id, wallet_account_id, merchant_id, merchant_account_id, amount, refunded_amount, created_at
            FROM payments WHERE id = :id
            """;

    private static final RowMapper<Payment> ROW = (rs, n) -> new Payment(
            rs.getObject("id", UUID.class),
            rs.getLong("wallet_account_id"),
            rs.getObject("merchant_id", UUID.class),
            rs.getLong("merchant_account_id"),
            rs.getLong("amount"),
            rs.getLong("refunded_amount"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Payment payment) {
        jdbc.sql("""
                        INSERT INTO payments (id, wallet_account_id, merchant_id, merchant_account_id, amount, created_at)
                        VALUES (:id, :wallet, :merchant, :merchantAccount, :amount, :createdAt)
                        """)
                .param("id", payment.id())
                .param("wallet", payment.walletAccountId())
                .param("merchant", payment.merchantId())
                .param("merchantAccount", payment.merchantAccountId())
                .param("amount", payment.amount())
                .param("createdAt", payment.createdAt())
                .update();
    }

    public Optional<Payment> findById(UUID id) {
        return jdbc.sql(SELECT).param("id", id).query(ROW).optional();
    }

    /** Locks the payment row until the transaction ends, so only one refund at a time can read and change it. */
    public Optional<Payment> lockForUpdate(UUID id) {
        return jdbc.sql(SELECT + " FOR UPDATE").param("id", id).query(ROW).optional();
    }

    public void addRefunded(UUID id, long amount) {
        jdbc.sql("UPDATE payments SET refunded_amount = refunded_amount + :amount WHERE id = :id")
                .param("id", id)
                .param("amount", amount)
                .update();
    }

    /** Refund entries for a payment, oldest first, with the amount returned to the wallet. */
    public List<RefundLine> refunds(Payment payment) {
        return jdbc.sql("""
                        SELECT j.id, p.amount, j.created_at
                        FROM journal_entries j
                        JOIN postings p ON p.entry_id = j.id AND p.account_id = :wallet
                        WHERE j.reference_id = :payment AND j.type = 'REFUND'
                        ORDER BY j.created_at, j.id
                        """)
                .param("wallet", payment.walletAccountId())
                .param("payment", payment.id())
                .query((rs, n) -> new RefundLine(
                        rs.getObject("id", UUID.class), rs.getLong("amount"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public record RefundLine(UUID refundId, long amount, OffsetDateTime createdAt) {
    }
}
