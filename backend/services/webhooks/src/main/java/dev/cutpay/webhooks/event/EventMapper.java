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
            case "PaymentAuthorized" -> payment("payment_intent.amount_capturable_updated", p);
            case "PaymentSucceeded" -> payment("payment_intent.succeeded", p);
            case "PaymentFailed" -> payment("payment_intent.payment_failed", p);
            case "PaymentCanceled" -> payment("payment_intent.canceled", p);
            case "RefundCreated" -> refund("refund.created", p);
            case "RefundSucceeded" -> refund("charge.refunded", p);
            case "RefundFailed" -> refund("refund.failed", p);
            case "PayoutCreated" -> payout("payout.created", p);
            case "PayoutPaid" -> payout("payout.paid", p);
            case "PayoutFailed" -> payout("payout.failed", p);
            case "SubscriptionCreated" -> subscription("customer.subscription.created", p);
            case "SubscriptionUpdated", "SubscriptionRenewed", "SubscriptionPastDue" ->
                    subscription("customer.subscription.updated", p);
            case "SubscriptionCanceled" -> subscription("customer.subscription.deleted", p);
            case "InvoiceFinalized" -> invoice("invoice.finalized", p);
            case "InvoicePaid" -> invoice("invoice.paid", p);
            case "InvoicePaymentFailed" -> invoice("invoice.payment_failed", p);
            case "InvoiceVoided" -> invoice("invoice.voided", p);
            case "CustomerCreated" -> customer("customer.created", p);
            case "CustomerUpdated" -> customer("customer.updated", p);
            case "CustomerDeleted" -> customer("customer.deleted", p);
            case "PaymentMethodAttached" -> paymentMethod("payment_method.attached", p);
            case "PaymentMethodDetached" -> paymentMethod("payment_method.detached", p);
            case "SetupIntentSucceeded" -> setupIntent("setup_intent.succeeded", p);
            case "SetupIntentFailed" -> setupIntent("setup_intent.setup_failed", p);
            case "PaymentLinkCreated" -> link("payment_link.created", p);
            case "PaymentLinkDeactivated" -> link("payment_link.deactivated", p);
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

    private static Optional<Mapped> subscription(String type, JsonNode p) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", text(p, "subscription_id"));
        object.put("object", "subscription");
        object.put("customer", text(p, "customer_id"));
        object.put("status", text(p, "status"));
        optional(object, "currency", p);
        optional(object, "current_period_end", p);
        optional(object, "latest_invoice", p);
        object.put("cancel_at_period_end", p.path("cancel_at_period_end").asBoolean(false));
        return Optional.of(new Mapped(type, object));
    }

    private static Optional<Mapped> invoice(String type, JsonNode p) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", text(p, "invoice_id"));
        object.put("object", "invoice");
        object.put("customer", text(p, "customer_id"));
        object.put("status", text(p, "status"));
        object.put("total", p.path("total").asLong());
        object.put("amount_paid", p.path("amount_paid").asLong());
        object.put("currency", text(p, "currency"));
        optional(object, "number", p);
        optional(object, "subscription_id", p);
        optional(object, "hosted_invoice_url", p);
        optional(object, "failure_code", p);
        return Optional.of(new Mapped(type, object));
    }

    private static Optional<Mapped> customer(String type, JsonNode p) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", text(p, "customer_id"));
        object.put("object", "customer");
        optional(object, "email", p);
        optional(object, "name", p);
        return Optional.of(new Mapped(type, object));
    }

    private static Optional<Mapped> paymentMethod(String type, JsonNode p) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", text(p, "payment_method_id"));
        object.put("object", "payment_method");
        object.put("customer", text(p, "customer_id"));
        optional(object, "brand", p);
        optional(object, "last4", p);
        return Optional.of(new Mapped(type, object));
    }

    private static Optional<Mapped> setupIntent(String type, JsonNode p) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", text(p, "setup_intent_id"));
        object.put("object", "setup_intent");
        object.put("customer", text(p, "customer_id"));
        object.put("status", text(p, "status"));
        if (!p.path("payment_method_id").isMissingNode() && !p.path("payment_method_id").isNull()) {
            object.put("payment_method", p.path("payment_method_id").asString());
        }
        optional(object, "failure_code", p);
        return Optional.of(new Mapped(type, object));
    }

    private static Optional<Mapped> link(String type, JsonNode p) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", text(p, "link_id"));
        object.put("object", "payment_link");
        object.put("slug", text(p, "slug"));
        optional(object, "url", p);
        optional(object, "type", p);
        optional(object, "title", p);
        optional(object, "currency", p);
        if (!p.path("amount").isMissingNode()) {
            object.put("amount", p.path("amount").asLong());
        }
        object.put("active", "payment_link.created".equals(type));
        optional(object, "reason", p);
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
