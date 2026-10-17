package dev.cutpay.ledger.events;

import dev.cutpay.ledger.config.Topics;
import dev.cutpay.ledger.posting.LedgerEvents.Payout;
import dev.cutpay.ledger.posting.LedgerEvents.PaymentSucceeded;
import dev.cutpay.ledger.posting.LedgerEvents.RefundSucceeded;
import dev.cutpay.ledger.posting.LedgerService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the JSON envelopes (docs/adr/0004-event-serialisation.md) and hands typed events to the
 * {@link LedgerService}. Parsing is separate from posting so the service can be tested without Kafka.
 *
 * <p>Each handler is one database transaction that also records the event id in the inbox. The listener
 * container commits the Kafka offset only after the handler returns, so a crash replays the event, finds
 * it in the inbox and skips it: effectively-once processing.
 */
@Component
class LedgerEventListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventListener.class);

    private final LedgerService ledger;
    private final JsonMapper mapper;

    LedgerEventListener(LedgerService ledger, JsonMapper mapper) {
        this.ledger = ledger;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.MERCHANT)
    void onMerchantEvent(String message) {
        JsonNode event = mapper.readTree(message);
        switch (type(event)) {
            case "MerchantCreated" -> ledger.onMerchantCreated(eventId(event),
                    text(event.path("payload"), "merchant_id"), text(event.path("payload"), "default_currency"));
            default -> log.trace("Ignoring {}", type(event));
        }
    }

    @KafkaListener(topics = Topics.PAYMENT)
    void onPaymentEvent(String message) {
        JsonNode event = mapper.readTree(message);
        if (!"PaymentSucceeded".equals(type(event))) {
            return;
        }
        JsonNode payload = event.path("payload");
        ledger.onPaymentSucceeded(new PaymentSucceeded(eventId(event), occurredAt(event), merchantId(event),
                livemode(event), text(payload, "payment_id"), payload.path("amount").asLong(),
                text(payload, "currency"), nullableText(payload, "card_type"), nullableText(payload, "card_region")));
    }

    @KafkaListener(topics = Topics.REFUND)
    void onRefundEvent(String message) {
        JsonNode event = mapper.readTree(message);
        if (!"RefundSucceeded".equals(type(event))) {
            return;
        }
        JsonNode payload = event.path("payload");
        ledger.onRefundSucceeded(new RefundSucceeded(eventId(event), occurredAt(event), merchantId(event),
                livemode(event), text(payload, "refund_id"), text(payload, "payment_id"),
                payload.path("amount").asLong(), text(payload, "currency")));
    }

    @KafkaListener(topics = Topics.PAYOUT)
    void onPayoutEvent(String message) {
        JsonNode event = mapper.readTree(message);
        JsonNode payload = event.path("payload");
        String type = type(event);
        switch (type) {
            case "PayoutCreated" -> ledger.onPayoutCreated(payout(event, payload));
            case "PayoutPaid" -> ledger.onPayoutPaid(payout(event, payload));
            case "PayoutFailed" -> ledger.onPayoutFailed(payout(event, payload));
            case "PayoutScheduleChanged" -> ledger.onPayoutScheduleChanged(eventId(event), merchantId(event),
                    payload.path("delay_days").asInt());
            default -> log.trace("Ignoring {}", type);
        }
    }

    private Payout payout(JsonNode event, JsonNode payload) {
        return new Payout(eventId(event), occurredAt(event), merchantId(event), livemode(event),
                text(payload, "payout_id"), payload.path("amount").asLong(), payload.path("fee").asLong(0),
                text(payload, "currency"));
    }

    private static String type(JsonNode event) {
        return text(event, "event_type");
    }

    private static String eventId(JsonNode event) {
        return text(event, "event_id");
    }

    private static String merchantId(JsonNode event) {
        return text(event, "merchant_id");
    }

    /** Events without a mode (merchant events) are treated as test mode. */
    private static boolean livemode(JsonNode event) {
        return event.path("livemode").asBoolean(false);
    }

    private static Instant occurredAt(JsonNode event) {
        return Instant.parse(text(event, "occurred_at"));
    }

    /** A required field: a missing one is a malformed event, which retrying cannot fix. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Event is missing required field '" + field + "'");
        }
        return value.asString();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
