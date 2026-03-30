package dev.ledger.webhook;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

import dev.ledger.common.BadRequestException;
import dev.ledger.common.ConflictException;
import dev.ledger.common.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookSubscriptionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public WebhookSubscriptionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Registers a callback URL and returns its signing secret. The secret is only ever shown here. */
    @Transactional
    public CreatedSubscription subscribe(UUID merchantId, String url) {
        URI uri = validate(url);
        boolean merchantExists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM merchants WHERE id = :id)")
                .param("id", merchantId).query(Boolean.class).single();
        if (!merchantExists) {
            throw new NotFoundException("MERCHANT_NOT_FOUND", "Merchant " + merchantId + " not found");
        }

        UUID id = UUID.randomUUID();
        String secret = newSecret();
        try {
            jdbc.sql("INSERT INTO webhook_subscriptions (id, merchant_id, url, secret) VALUES (:id, :merchant, :url, :secret)")
                    .param("id", id)
                    .param("merchant", merchantId)
                    .param("url", uri.toString())
                    .param("secret", secret)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("SUBSCRIPTION_EXISTS", "This merchant already has a webhook for that URL");
        }
        return new CreatedSubscription(id, merchantId, uri.toString(), secret);
    }

    private static URI validate(String url) {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new BadRequestException("INVALID_URL", "url must be an absolute http(s) URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new BadRequestException("INVALID_URL", "url must be an absolute http(s) URL");
        }
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }

    public record CreatedSubscription(UUID subscriptionId, UUID merchantId, String url, String secret) {
    }
}
