package dev.ledger.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code Ledger-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}
 * <p>
 * Signing the timestamp with the body lets a merchant reject replays of an old, captured request:
 * verify the HMAC, then check {@code t} is within a few minutes of now.
 */
public final class WebhookSignature {

    public static final String HEADER = "Ledger-Signature";

    private WebhookSignature() {
    }

    public static String header(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + hmac(secret, timestampSeconds + "." + body);
    }

    /** What a merchant should run on every webhook it receives. */
    public static boolean verify(String secret, String header, String body, long nowSeconds, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        Long timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                signature = kv[1];
            }
        }
        if (timestamp == null || signature == null || Math.abs(nowSeconds - timestamp) > toleranceSeconds) {
            return false;
        }
        byte[] expected = hmac(secret, timestamp + "." + body).getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison: don't leak how many leading characters matched.
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", e);
        }
    }
}
