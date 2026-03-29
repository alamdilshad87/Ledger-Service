package dev.ledger.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Test helper: reads the ledger-events topic from the beginning with a throwaway consumer group. */
final class KafkaEvents implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    KafkaEvents(String bootstrapServers, String topic) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
    }

    /** All records with this Kafka key seen within {@code wait}. */
    List<ConsumerRecord<String, String>> forKey(String key, int expected, Duration wait) {
        return await(r -> key.equals(r.key()), expected, wait);
    }

    List<ConsumerRecord<String, String>> await(Predicate<ConsumerRecord<String, String>> match, int expected, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline && seen.stream().filter(match).count() < expected) {
            consumer.poll(Duration.ofMillis(200)).forEach(seen::add);
        }
        return seen.stream().filter(match).toList();
    }

    static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        consumer.close();
    }
}
