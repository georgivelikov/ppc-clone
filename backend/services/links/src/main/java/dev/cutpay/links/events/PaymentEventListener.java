package dev.cutpay.links.events;

import dev.cutpay.links.config.Topics;
import dev.cutpay.links.link.LinkService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns payment results into link usage. Only payments that carry a {@code link_id} in their metadata matter.
 * Each handler runs in one transaction with an inbox check, so a redelivered event changes nothing.
 */
@Component
class PaymentEventListener {

    private final LinkService links;
    private final JsonMapper mapper;

    PaymentEventListener(LinkService links, JsonMapper mapper) {
        this.links = links;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.PAYMENT)
    void onPaymentEvent(String message) {
        JsonNode event = mapper.readTree(message);
        JsonNode payload = event.path("payload");
        JsonNode metadata = payload.path("metadata");
        String linkId = text(metadata, "link_id");
        if (linkId == null) {
            return;
        }
        String eventId = event.path("event_id").asString();
        String reservationId = text(metadata, "link_reservation_id");
        switch (event.path("event_type").asString()) {
            case "PaymentSucceeded" -> links.onPaymentSucceeded(eventId, linkId, reservationId,
                    payload.path("amount").asLong());
            case "PaymentFailed", "PaymentCanceled" -> links.onPaymentEnded(eventId, reservationId);
            default -> { }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
