package dev.ledger.user;

import java.util.Locale;
import java.util.UUID;

import dev.ledger.account.AccountRepository;
import dev.ledger.common.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final JdbcClient jdbc;
    private final AccountRepository accounts;

    public UserService(JdbcClient jdbc, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    /** Creates the user and an empty INR wallet in one transaction: either both exist or neither. */
    @Transactional
    public CreatedUser createWithWallet(String email) {
        UUID userId = UUID.randomUUID();
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        try {
            jdbc.sql("INSERT INTO users (id, email) VALUES (:id, :email)")
                    .param("id", userId)
                    .param("email", normalized)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("EMAIL_EXISTS", "A user with this email already exists");
        }
        long walletId = accounts.createWallet(userId, "INR");
        return new CreatedUser(userId, normalized, walletId);
    }

    public record CreatedUser(UUID userId, String email, long walletAccountId) {
    }
}
