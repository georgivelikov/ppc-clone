package dev.cutpay.events;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores an event in the service's {@code outbox} table. Call it inside the same transaction as the
 * business change; {@link OutboxRelay} publishes it to Kafka afterwards (docs/adr/0005-outbox-relay.md).
 */
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final TraceLink trace;

    public OutboxWriter(JdbcClient jdbc, JsonMapper mapper, TraceLink trace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.trace = trace;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> String write(String topic, String eventType, String aggregateId, String merchantId,
                            Boolean livemode, T payload) {
        String eventId = "evt_" + UUID.randomUUID();
        var envelope = new EventEnvelope<>(eventId, eventType, Instant.now(), aggregateId, merchantId, livemode, 1,
                payload);
        jdbc.sql("""
                        INSERT INTO outbox (event_id, topic, event_key, event_type, payload, traceparent)
                        VALUES (:eventId, :topic, :key, :type, :payload, :traceparent)
                        """)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("key", aggregateId)
                .param("type", eventType)
                .param("payload", mapper.writeValueAsString(envelope))
                .param("traceparent", trace.current())
                .update();
        return eventId;
    }
}
