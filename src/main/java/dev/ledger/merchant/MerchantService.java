package dev.ledger.merchant;

import java.util.UUID;

import dev.ledger.account.AccountRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private final JdbcClient jdbc;
    private final AccountRepository accounts;

    public MerchantService(JdbcClient jdbc, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    /** Creates the merchant and its INR settlement account together. */
    @Transactional
    public CreatedMerchant create(String name) {
        UUID id = UUID.randomUUID();
        String trimmed = name.trim();
        jdbc.sql("INSERT INTO merchants (id, name) VALUES (:id, :name)")
                .param("id", id)
                .param("name", trimmed)
                .update();
        long accountId = accounts.createMerchantAccount(id, "INR");
        return new CreatedMerchant(id, trimmed, accountId);
    }

    public record CreatedMerchant(UUID merchantId, String name, long accountId) {
    }
}
