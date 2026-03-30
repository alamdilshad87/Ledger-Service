package dev.ledger.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import dev.ledger.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WebhookSubscriptionApiTest extends ApiTestSupport {

    @Test
    void subscribingReturnsASigningSecret() {
        Merchant shop = newMerchant();

        var response = post("/v1/webhooks", Map.of("merchantId", shop.id(), "url", "https://shop.example.com/hooks"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("url", "https://shop.example.com/hooks").containsKey("subscriptionId");
        assertThat((String) response.getBody().get("secret")).matches("whsec_[0-9a-f]{64}");
    }

    @Test
    void sameUrlTwiceIs409() {
        Merchant shop = newMerchant();
        post("/v1/webhooks", Map.of("merchantId", shop.id(), "url", "https://shop.example.com/a"));

        var again = post("/v1/webhooks", Map.of("merchantId", shop.id(), "url", "https://shop.example.com/a"));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "SUBSCRIPTION_EXISTS");
    }

    @Test
    void invalidUrlsAre400() {
        Merchant shop = newMerchant();

        for (String url : new String[] {"ftp://shop.example.com", "not a url", "/relative/path", "https://"}) {
            var response = post("/v1/webhooks", Map.of("merchantId", shop.id(), "url", url));
            assertThat(response.getStatusCode()).as(url).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void unknownMerchantIs404() {
        var response = post("/v1/webhooks", Map.of("merchantId", UUID.randomUUID(), "url", "https://x.example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
