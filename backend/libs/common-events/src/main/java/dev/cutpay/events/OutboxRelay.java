package dev.cutpay.events;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the {@code outbox} table and publishes unsent events to Kafka.
 *
 * <p>Rows are locked with {@code FOR UPDATE SKIP LOCKED}, so several instances can run safely. Delivery
 * is at-least-once: if the process dies after Kafka acknowledged a record but before {@code published_at}
 * is set, the event is sent again. Consumers must deduplicate with {@link InboxGuard}.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final TraceLink trace;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       TraceLink trace) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.trace = trace;
    }

    @Scheduled(fixedDelayString = "${cutpay.outbox.poll-interval:PT0.5S}")
    public void relay() {
        int sent;
        do {
            Integer count = tx.execute(status -> publishBatch());
            sent = count == null ? 0 : count;
        } while (sent == BATCH_SIZE);
    }

    private int publishBatch() {
        List<OutboxRow> rows = jdbc.sql("""
                        SELECT id, event_id, topic, event_key, event_type, payload, traceparent
                        FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", BATCH_SIZE)
                .query((rs, n) -> new OutboxRow(rs.getLong("id"), rs.getString("event_id"), rs.getString("topic"),
                        rs.getString("event_key"), rs.getString("event_type"), rs.getString("payload"),
                        rs.getString("traceparent")))
                .list();

        for (OutboxRow row : rows) {
            var record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
            record.headers().add("event_id", row.eventId().getBytes(StandardCharsets.UTF_8));
            record.headers().add("event_type", row.eventType().getBytes(StandardCharsets.UTF_8));
            try {
                trace.within("outbox publish", row.traceparent(), () -> send(record));
            } catch (Exception e) {
                // Roll back so published_at stays NULL for this batch; the next poll retries it.
                log.warn("Failed to publish outbox event {} to {}: {}", row.eventId(), row.topic(), e.toString());
                throw new IllegalStateException("Outbox publish failed", e);
            }
            jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :id").param("id", row.id()).update();
        }
        return rows.size();
    }

    private void send(ProducerRecord<String, String> record) {
        try {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing to " + record.topic(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    private record OutboxRow(long id, String eventId, String topic, String key, String eventType, String payload,
                           String traceparent) {
    }
}
