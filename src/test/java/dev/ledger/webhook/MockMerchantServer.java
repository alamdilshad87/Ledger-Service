package dev.ledger.webhook;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * A merchant's webhook endpoint for tests. It fails a configurable share of requests with 500,
 * verifies every signature, and dedupes by Ledger-Event-Id the way a real merchant should.
 */
final class MockMerchantServer implements AutoCloseable {

    private final HttpServer server;
    private final double failureRate;
    private final Random random;
    private volatile String secret;

    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger failuresReturned = new AtomicInteger();
    final AtomicInteger badSignatures = new AtomicInteger();
    final AtomicInteger duplicatesIgnored = new AtomicInteger();
    /** Event id → how many times it was accepted (answered 2xx). */
    final Map<String, AtomicInteger> accepted = new ConcurrentHashMap<>();
    /** Event id → how many times its effect was applied after the merchant's own dedupe. */
    final Map<String, AtomicInteger> processed = new ConcurrentHashMap<>();

    MockMerchantServer(double failureRate, long seed) throws IOException {
        this.failureRate = failureRate;
        this.random = new Random(seed);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hooks", exchange -> {
            requests.incrementAndGet();
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String eventId = exchange.getRequestHeaders().getFirst("Ledger-Event-Id");
            String signature = exchange.getRequestHeaders().getFirst(WebhookSignature.HEADER);
            int status;
            if (!WebhookSignature.verify(secret, signature, body, Instant.now().getEpochSecond(), 300)) {
                badSignatures.incrementAndGet();
                status = 400;
            } else if (nextFails()) {
                failuresReturned.incrementAndGet();
                status = 500;
            } else {
                accepted.computeIfAbsent(eventId, k -> new AtomicInteger()).incrementAndGet();
                // Merchant-side dedupe: apply each event id once, however many times it arrives.
                if (processed.putIfAbsent(eventId, new AtomicInteger(1)) != null) {
                    duplicatesIgnored.incrementAndGet();
                }
                status = 200;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    private synchronized boolean nextFails() {
        return random.nextDouble() < failureRate;
    }

    void useSecret(String secret) {
        this.secret = secret;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
