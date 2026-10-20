package dev.cutpay.payouts.events;

import dev.cutpay.payouts.config.Topics;
import dev.cutpay.payouts.payout.PayoutService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds the payable-balance projection from the ledger's {@code FundsAvailable} events and creates the
 * default schedule for new merchants. Each handler is one transaction with an inbox check, so redelivered
 * events change nothing.
 */
@Component
class PayoutsEventListener {

    private final PayoutService payouts;
    private final JsonMapper mapper;

    PayoutsEventListener(PayoutService payouts, JsonMapper mapper) {
        this.payouts = payouts;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.MERCHANT)
    void onMerchantEvent(String message) {
        JsonNode event = mapper.readTree(message);
        String type = text(event, "event_type");
        if ("PayoutsFrozen".equals(type) || "PayoutsUnfrozen".equals(type)) {
            payouts.onFreezeChanged(text(event, "event_id"), text(event, "merchant_id"), "PayoutsFrozen".equals(type));
        } else if ("MerchantCreated".equals(type)) {
            payouts.onMerchantCreated(text(event, "event_id"), text(event, "merchant_id"));
        } else if ("ConnectedAccountCreated".equals(type) || "ConnectedAccountUpdated".equals(type)) {
            JsonNode payload = event.path("payload");
            payouts.onConnectedAccount(text(event, "event_id"), text(payload, "account_id"),
                    event.path("livemode").asBoolean(false), text(payload, "iban"), text(payload, "name"),
                    text(payload, "currency"), "ConnectedAccountUpdated".equals(type));
        }
    }

    @KafkaListener(topics = Topics.LEDGER_ENTRIES)
    void onLedgerEvent(String message) {
        JsonNode event = mapper.readTree(message);
        if (!"FundsAvailable".equals(text(event, "event_type"))) {
            return;
        }
        JsonNode payload = event.path("payload");
        payouts.onFundsAvailable(text(event, "event_id"), text(event, "merchant_id"),
                event.path("livemode").asBoolean(false), text(payload, "currency"), text(payload, "entry_id"),
                text(payload, "source_id"), payload.path("amount").asLong());
    }

    /** A required field: a missing one is a malformed event, which retrying cannot fix. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Event is missing required field '" + field + "'");
        }
        return value.asString();
    }
}
