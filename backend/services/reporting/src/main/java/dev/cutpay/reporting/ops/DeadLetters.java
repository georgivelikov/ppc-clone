package dev.cutpay.reporting.ops;

import dev.cutpay.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The back-office view of every {@code *.dlt} topic: records the services gave up on after their retries. A person can
 * send one back to the topic it came from (every consumer treats an event it already handled as a duplicate, so only
 * the one that failed does the work) or discard it. The record stays on the dead-letter topic either way; what was
 * decided is kept in {@code dead_letter_actions}.
 */
@Component
public class DeadLetters {

    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);
    private static final String ORIGINAL_TOPIC = "kafka_dlt-original-topic";
    private static final String EXCEPTION_MESSAGE = "kafka_dlt-exception-message";
    private static final int MAX_LISTED = 200;

    public record Letter(String topic, int partition, long offset, String originalTopic, String key, String eventType,
                         String error, Instant at, String payload) {
    }

    private final ConsumerFactory<String, String> consumers;
    private final KafkaTemplate<String, String> kafka;
    private final JdbcClient jdbc;

    DeadLetters(ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka, JdbcClient jdbc) {
        this.consumers = consumers;
        this.kafka = kafka;
        this.jdbc = jdbc;
    }

    /** Records nobody has acted on yet, oldest first. */
    public List<Letter> waiting() {
        Set<String> handled = new HashSet<>(
                jdbc.sql("SELECT topic || '/' || partition_no || '/' || record_offset FROM dead_letter_actions")
                        .query(String.class).list());
        List<Letter> out = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("dlt-admin-" + UUID.randomUUID(), "")) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (Map.Entry<String, List<PartitionInfo>> topic : consumer.listTopics().entrySet()) {
                if (topic.getKey().endsWith(".dlt")) {
                    topic.getValue().forEach(p -> partitions.add(new TopicPartition(p.topic(), p.partition())));
                }
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            while (!reachedEnd(consumer, partitions, end)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    if (!handled.contains(record.topic() + "/" + record.partition() + "/" + record.offset())) {
                        out.add(letter(record));
                    }
                }
            }
        }
        out.sort(Comparator.comparing(Letter::at));
        return out.size() > MAX_LISTED ? out.subList(0, MAX_LISTED) : out;
    }

    /** Sends the record back to the topic it came from. */
    public Letter redrive(String topic, int partition, long offset, String staffEmail) {
        ConsumerRecord<String, String> record = fetch(topic, partition, offset);
        Letter letter = letter(record);
        if (letter.originalTopic() == null) {
            throw ApiException.invalidRequest("no_original_topic", "This record does not say where it came from", "topic");
        }
        var resend = new ProducerRecord<>(letter.originalTopic(), record.key(), record.value());
        for (String name : List.of("event_id", "event_type")) {
            Header header = record.headers().lastHeader(name);
            if (header != null) {
                resend.headers().add(name, header.value());
            }
        }
        try {
            kafka.send(resend).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not send the record back to " + letter.originalTopic(), e);
        }
        note(topic, partition, offset, "redriven", staffEmail);
        log.info("Dead letter {}-{}@{} sent back to {} by {}", topic, partition, offset, letter.originalTopic(), staffEmail);
        return letter;
    }

    public void discard(String topic, int partition, long offset, String staffEmail) {
        fetch(topic, partition, offset);
        note(topic, partition, offset, "discarded", staffEmail);
        log.info("Dead letter {}-{}@{} discarded by {}", topic, partition, offset, staffEmail);
    }

    private void note(String topic, int partition, long offset, String action, String staffEmail) {
        int inserted = jdbc.sql("""
                        INSERT INTO dead_letter_actions (topic, partition_no, record_offset, action, staff_email)
                        VALUES (:topic, :partition, :offset, :action, :email)
                        ON CONFLICT DO NOTHING
                        """)
                .param("topic", topic).param("partition", partition).param("offset", offset).param("action", action)
                .param("email", staffEmail).update();
        if (inserted == 0) {
            throw ApiException.conflict("already_handled", "Someone already dealt with this record", null);
        }
    }

    private ConsumerRecord<String, String> fetch(String topic, int partition, long offset) {
        if (!topic.endsWith(".dlt")) {
            throw ApiException.invalidRequest("not_a_dead_letter_topic", "Only .dlt topics can be handled here", "topic");
        }
        var tp = new TopicPartition(topic, partition);
        try (Consumer<String, String> consumer = consumers.createConsumer("dlt-admin-" + UUID.randomUUID(), "")) {
            consumer.assign(List.of(tp));
            consumer.seek(tp, offset);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    if (record.offset() == offset) {
                        return record;
                    }
                }
            }
        }
        throw ApiException.notFound("dead_letter_not_found", "No such record on " + topic);
    }

    private static boolean reachedEnd(Consumer<String, String> consumer, List<TopicPartition> partitions,
                                      Map<TopicPartition, Long> end) {
        return partitions.stream().allMatch(tp -> consumer.position(tp) >= end.getOrDefault(tp, 0L));
    }

    private static Letter letter(ConsumerRecord<String, String> record) {
        return new Letter(record.topic(), record.partition(), record.offset(), header(record, ORIGINAL_TOPIC), record.key(),
                header(record, "event_type"), header(record, EXCEPTION_MESSAGE), Instant.ofEpochMilli(record.timestamp()),
                record.value());
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
