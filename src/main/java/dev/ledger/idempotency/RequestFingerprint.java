package dev.ledger.idempotency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

/**
 * SHA-256 of what a request asks for: method, path and the JSON body in canonical form.
 * <p>
 * The body is re-serialised with keys sorted and whitespace removed, so
 * {@code {"amount":100,"accountId":2}} and {@code { "accountId": 2, "amount": 100 }} are the same request.
 * A body that isn't valid JSON is hashed as raw bytes; the controller will reject it with 400 anyway.
 */
@Component
public class RequestFingerprint {

    private final ObjectMapper canonical = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String of(String method, String path, byte[] body) {
        MessageDigest sha256 = sha256();
        sha256.update((method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
        sha256.update(canonicalise(body));
        return HexFormat.of().formatHex(sha256.digest());
    }

    private byte[] canonicalise(byte[] body) {
        if (body.length == 0) {
            return body;
        }
        try {
            return canonical.writeValueAsBytes(canonical.readValue(body, Object.class));
        } catch (IOException notJson) {
            return body;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
