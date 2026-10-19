package dev.cutpay.reporting.projection;

import dev.cutpay.reporting.config.Topics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Throws the read model away and builds it again from the start of the topics. This is what makes it a projection and not a
 * second source of truth: the facts live in Kafka, and any table here can be recreated from them.
 *
 * <p>It reads with a consumer of its own that joins no group and commits nothing, so the live listeners are untouched. They
 * may run during a rebuild: an event they apply is simply applied again by the replay (the upserts are safe in any order).
 */
@Component
public class Rebuilder {

    private static final Logger log = LoggerFactory.getLogger(Rebuilder.class);

    public record Result(long events, long applied) {
    }

    private final ConsumerFactory<String, String> consumers;
    private final Projector projector;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper mapper;

    public Rebuilder(ConsumerFactory<String, String> consumers, Projector projector, JdbcClient jdbc, TransactionTemplate tx,
                     JsonMapper mapper) {
        this.consumers = consumers;
        this.projector = projector;
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
    }

    public synchronized Result rebuild() {
        tx.executeWithoutResult(status -> {
            for (String table : List.of("fact_payments", "fact_balance_transactions", "fact_payouts", "fact_disputes", "processed_events")) {
                jdbc.sql("DELETE FROM " + table).update();
            }
        });
        long events = 0;
        long applied = 0;
        try (Consumer<String, String> consumer = consumers.createConsumer("reporting-rebuild-" + System.nanoTime(), "rebuild")) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (String topic : Topics.SOURCES) {
                var infos = consumer.partitionsFor(topic, Duration.ofSeconds(10));
                if (infos != null) {
                    infos.forEach(i -> partitions.add(new TopicPartition(topic, i.partition())));
                }
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = new HashMap<>(consumer.endOffsets(partitions));
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    events++;
                    if (projector.apply(record.topic(), mapper.readTree(record.value()))) {
                        applied++;
                    }
                }
            }
        }
        log.info("Rebuilt the read model from {} events ({} used)", events, applied);
        return new Result(events, applied);
    }
}
