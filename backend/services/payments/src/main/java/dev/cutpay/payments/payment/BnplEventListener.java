package dev.cutpay.payments.payment;

import dev.cutpay.events.InboxGuard;
import dev.cutpay.payments.config.KafkaTopicsConfig;
import dev.cutpay.payments.payment.PaymentService.BnplApproval;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settles payments that a buy-now-pay-later provider approved. Only {@code BnplApproved} matters here: a declined or
 * expired application leaves the payment open, so the customer can pay another way.
 *
 * <p>Each event is handled in one transaction that also records it in the inbox, so a redelivery does nothing.
 */
@Component
class BnplEventListener {

    private final PaymentService payments;
    private final InboxGuard inbox;
    private final JsonMapper mapper;

    BnplEventListener(PaymentService payments, InboxGuard inbox, JsonMapper mapper) {
        this.payments = payments;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    @KafkaListener(topics = KafkaTopicsConfig.BNPL_TOPIC, groupId = "payments-bnpl")
    @Transactional
    void onBnplEvent(String message) {
        JsonNode event = mapper.readTree(message);
        if (!"BnplApproved".equals(event.path("event_type").asString())) {
            return;
        }
        if (!inbox.firstTime(event.path("event_id").asString())) {
            return;
        }
        JsonNode payload = event.path("payload");
        payments.applyBnplApproval(required(payload, "payment_id"), new BnplApproval(required(payload, "application_id"),
                required(payload, "provider"), payload.path("amount").asLong(), required(payload, "currency"),
                payload.path("merchant_fee").asLong()));
    }

    /** A missing field is a malformed event, which retrying cannot fix. */
    private static String required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Event is missing required field '" + field + "'");
        }
        return value.asString();
    }
}
