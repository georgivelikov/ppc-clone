package dev.cutpay.webhooks.event;

import dev.cutpay.webhooks.config.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the public-facing topics and hands each event to {@link EventService}. */
@Component
class WebhookEventListener {

    private final EventService events;
    private final JsonMapper mapper;

    WebhookEventListener(EventService events, JsonMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    @KafkaListener(topics = {Topics.PAYMENT, Topics.REFUND, Topics.PAYOUT})
    void onEvent(String message) {
        JsonNode event = mapper.readTree(message);
        events.ingest(text(event, "event_id"), text(event, "event_type"), text(event, "merchant_id"),
                event.path("livemode").asBoolean(false), event.path("payload"));
    }

    /** A required field: a missing one is a malformed event, which retrying cannot fix. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Event is missing required field " + field);
        }
        return value.asString();
    }
}
