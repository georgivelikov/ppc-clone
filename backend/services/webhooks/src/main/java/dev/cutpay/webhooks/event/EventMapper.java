package dev.cutpay.webhooks.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Turns the platform's internal events into the public ones merchants receive. Internal payloads can change
 * freely; what is built here is the contract, so only fields meant for merchants are copied across.
 */
public final class EventMapper {

    /** The public type and the {@code data.object} for one internal event. */
    public record Mapped(String type, Map<String, Object> object) {
    }

    private EventMapper() {
    }

    public static Optional<Mapped> map(String sourceType, JsonNode p) {
        return switch (sourceType) {
            case "PaymentIntentCreated" -> payment("payment_intent.created", p);
            case "PaymentRequiresAction" -> payment("payment_intent.requires_action", p);
            case "PaymentSucceeded" -> payment("payment_intent.succeeded", p);
            case "PaymentFailed" -> payment("payment_intent.payment_failed", p);
            case "PaymentCanceled" -> payment("payment_intent.canceled", p);
            case "RefundCreated" -> refund("refund.created", p);
            case "RefundSucceeded" -> refund("charge.refunded", p);
            case "RefundFailed" -> refund("refund.failed", p);
            case "PayoutCreated" -> payout("payout.created", p);
            case "PayoutPaid" -> payout("payout.paid", p);
            case "PayoutFailed" -> payout("payout.failed", p);
            default -> Optional.empty();
        };
    }

    private static Optional<Mapped> payment(String type, JsonNode p) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", text(p, "payment_id"));
        object.put("object", "payment_intent");
        object.put("status", text(p, "status"));
        object.put("amount", p.path("amount").asLong());
        object.put("currency", text(p, "currency"));
        optional(object, "customer_email", p);
        optional(object, "failure_code", p);
        optional(object, "card_brand", p);
        return Optional.of(new Mapped(type, object));
    }

    private static Optional<Mapped> refund(String type, JsonNode p) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", text(p, "refund_id"));
        object.put("object", "refund");
        object.put("payment_intent", text(p, "payment_id"));
        object.put("status", text(p, "status"));
        object.put("amount", p.path("amount").asLong());
        object.put("currency", text(p, "currency"));
        optional(object, "failure_code", p);
        return Optional.of(new Mapped(type, object));
    }

    private static Optional<Mapped> payout(String type, JsonNode p) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", text(p, "payout_id"));
        object.put("object", "payout");
        object.put("status", text(p, "status"));
        object.put("amount", p.path("amount").asLong());
        object.put("fee", p.path("fee").asLong());
        object.put("currency", text(p, "currency"));
        optional(object, "arrival_date", p);
        optional(object, "kind", p);
        optional(object, "failure_code", p);
        return Optional.of(new Mapped(type, object));
    }

    /** A required field: a missing one is a malformed event, which retrying cannot fix. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Event is missing required field " + field);
        }
        return value.asString();
    }

    private static void optional(Map<String, Object> target, String field, JsonNode source) {
        JsonNode value = source.path(field);
        if (!value.isMissingNode() && !value.isNull()) {
            target.put(field, value.asString());
        }
    }
}
