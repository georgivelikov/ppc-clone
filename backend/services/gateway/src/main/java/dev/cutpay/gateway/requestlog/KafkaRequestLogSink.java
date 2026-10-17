package dev.cutpay.gateway.requestlog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes to {@code api.requests} in the same envelope the other services use. Sending is asynchronous and
 * bounded by short Kafka timeouts; when Kafka is down the entry is dropped (and noted at debug level), because
 * a log line is never worth failing or slowing a merchant API call.
 */
@Component
@ConditionalOnProperty(name = "cutpay.request-log.enabled", havingValue = "true", matchIfMissing = true)
class KafkaRequestLogSink implements RequestLogSink {

    static final String TOPIC = "api.requests";

    private static final Logger log = LoggerFactory.getLogger(KafkaRequestLogSink.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper mapper;

    KafkaRequestLogSink(KafkaTemplate<String, String> kafka, JsonMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Bean
    NewTopic apiRequestsTopic() {
        return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
    }

    @Override
    public void publish(RequestLogEntry entry) {
        try {
            kafka.send(TOPIC, entry.merchantId(), toJson(entry)).whenComplete((result, error) -> {
                if (error != null) {
                    log.debug("Dropped a request log entry: {}", error.toString());
                }
            });
        } catch (RuntimeException e) {
            log.debug("Dropped a request log entry: {}", e.toString());
        }
    }

    String toJson(RequestLogEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", entry.method());
        payload.put("path", entry.path());
        payload.put("status", entry.status());
        payload.put("latency_ms", entry.latencyMs());
        payload.put("actor", entry.actor());
        payload.put("actor_type", entry.actorType());
        payload.put("idempotency_key", entry.idempotencyKey());
        payload.put("request_id", entry.requestId());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", "evt_" + UUID.randomUUID());
        envelope.put("event_type", "ApiRequestLogged");
        envelope.put("occurred_at", entry.at().toString());
        envelope.put("aggregate_id", entry.requestId() == null ? "none" : entry.requestId());
        envelope.put("merchant_id", entry.merchantId());
        envelope.put("livemode", entry.livemode());
        envelope.put("version", 1);
        envelope.put("payload", payload);
        return mapper.writeValueAsString(envelope);
    }
}
