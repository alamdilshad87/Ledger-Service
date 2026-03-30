package dev.ledger.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final String SECRET = "whsec_test";
    private static final String BODY = "{\"eventId\":\"e1\",\"data\":{\"amount\":500}}";
    private static final long NOW = 1_800_000_000L;

    @Test
    void validSignatureVerifies() {
        String header = WebhookSignature.header(SECRET, NOW, BODY);

        assertThat(header).matches("t=1800000000,v1=[0-9a-f]{64}");
        assertThat(WebhookSignature.verify(SECRET, header, BODY, NOW + 10, 300)).isTrue();
    }

    @Test
    void tamperedBodyFails() {
        String header = WebhookSignature.header(SECRET, NOW, BODY);

        assertThat(WebhookSignature.verify(SECRET, header, BODY.replace("500", "50000"), NOW, 300)).isFalse();
    }

    @Test
    void wrongSecretFails() {
        String header = WebhookSignature.header(SECRET, NOW, BODY);

        assertThat(WebhookSignature.verify("whsec_other", header, BODY, NOW, 300)).isFalse();
    }

    @Test
    void replayOutsideToleranceFails() {
        String header = WebhookSignature.header(SECRET, NOW, BODY);

        assertThat(WebhookSignature.verify(SECRET, header, BODY, NOW + 301, 300)).isFalse();
    }

    @Test
    void timestampCannotBeSwappedWithoutBreakingTheSignature() {
        String header = WebhookSignature.header(SECRET, NOW, BODY);
        String forged = header.replace("t=" + NOW, "t=" + (NOW + 1000));

        assertThat(WebhookSignature.verify(SECRET, forged, BODY, NOW + 1000, 300)).isFalse();
    }

    @Test
    void malformedHeadersFail() {
        assertThat(WebhookSignature.verify(SECRET, null, BODY, NOW, 300)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "garbage", BODY, NOW, 300)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "t=abc,v1=00", BODY, NOW, 300)).isFalse();
    }
}
