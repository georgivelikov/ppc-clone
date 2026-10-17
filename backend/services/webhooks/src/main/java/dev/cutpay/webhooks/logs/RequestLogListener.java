package dev.cutpay.webhooks.logs;

import dev.cutpay.webhooks.config.Topics;
import java.time.Instant;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class RequestLogListener {

    private final RequestLogService logs;
    private final JsonMapper mapper;

    RequestLogListener(RequestLogService logs, JsonMapper mapper) {
        this.logs = logs;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.API_REQUESTS)
    void onRequest(String message) {
        JsonNode event = mapper.readTree(message);
        logs.ingest(text(event, "event_id"), text(event, "merchant_id"), event.path("livemode").asBoolean(false),
                Instant.parse(text(event, "occurred_at")), event.path("payload"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Event is missing required field " + field);
        }
        return value.asString();
    }
}
