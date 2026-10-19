package dev.cutpay.notifications.events;

import static dev.cutpay.notifications.events.EmailRequestListener.text;

import dev.cutpay.notifications.config.Topics;
import dev.cutpay.notifications.log.NotificationService;
import dev.cutpay.notifications.log.NotificationService.Request;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Turns what happened elsewhere (a payout was paid, a dispute opened) into emails. */
@Component
class DomainEventListener {

    private final NotificationService notifications;
    private final JsonMapper mapper;

    DomainEventListener(NotificationService notifications, JsonMapper mapper) {
        this.notifications = notifications;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.MERCHANT)
    void onMerchant(String message) {
        JsonNode event = mapper.readTree(message);
        JsonNode p = event.path("payload");
        String merchantId = text(event, "merchant_id");
        switch (event.path("event_type").asString()) {
            case "MerchantCreated" -> {
                notifications.rememberMerchant(merchantId, p.path("legal_name").asString());
                if (text(p, "owner_email") != null) {
                    notifications.upsertContact(merchantId, p.path("owner_user_id").asString(),
                            p.path("owner_email").asString(), text(p, "owner_name"), "owner", "en");
                }
            }
            case "TeamMemberJoined", "TeamRoleChanged" -> notifications.upsertContact(merchantId,
                    p.path("user_id").asString(), p.path("email").asString(), text(p, "full_name"),
                    p.path("role").asString(), "en");
            case "TeamMemberRemoved" -> notifications.removeContact(merchantId, p.path("user_id").asString());
            default -> { }
        }
    }

    @KafkaListener(topics = Topics.PAYOUT)
    void onPayout(String message) {
        JsonNode event = mapper.readTree(message);
        if (!"PayoutPaid".equals(event.path("event_type").asString())) {
            return;
        }
        JsonNode p = event.path("payload");
        Map<String, Object> data = new HashMap<>();
        data.put("amount", major(p.path("amount").asLong()));
        data.put("currency", p.path("currency").asString());
        data.put("arrival", text(p, "arrival_date") != null ? text(p, "arrival_date") : text(p, "arrival"));
        data.put("payout_url", notifications.dashboardUrl() + "/payouts/" + event.path("aggregate_id").asString());
        notifications.handle(new Request(id(event), text(event, "merchant_id"), null, "owners", "payout_paid", null, data));
    }

    @KafkaListener(topics = Topics.PAYMENT)
    void onPayment(String message) {
        JsonNode event = mapper.readTree(message);
        if (!"PaymentSucceeded".equals(event.path("event_type").asString())) {
            return;
        }
        JsonNode p = event.path("payload");
        String merchantId = text(event, "merchant_id");
        Map<String, Object> data = new HashMap<>();
        data.put("amount", major(p.path("amount").asLong()));
        data.put("currency", p.path("currency").asString());
        data.put("payment_id", event.path("aggregate_id").asString());
        data.put("merchant_name", notifications.merchantName(merchantId));
        data.put("payment_url", notifications.dashboardUrl() + "/payments/" + event.path("aggregate_id").asString());
        if (text(p, "customer_email") != null) {
            notifications.handle(new Request(id(event), merchantId, p.path("customer_email").asString(), null,
                    "payment_receipt", text(p, "locale"), data));
        }
        notifications.handle(new Request(id(event), merchantId, null, "owners", "payment_new", null, data));
    }

    @KafkaListener(topics = Topics.DISPUTE)
    void onDispute(String message) {
        JsonNode event = mapper.readTree(message);
        JsonNode p = event.path("payload");
        Map<String, Object> data = new HashMap<>();
        data.put("amount", major(p.path("amount").asLong()));
        data.put("currency", p.path("currency").asString());
        data.put("payment_id", p.path("payment_id").asString());
        data.put("dispute_url", notifications.dashboardUrl() + "/disputes/" + event.path("aggregate_id").asString());
        switch (event.path("event_type").asString()) {
            case "DisputeOpened" -> {
                data.put("due_by", p.path("evidence_due_by").asString());
                notifications.handle(new Request(id(event), text(event, "merchant_id"), null, "owners",
                        "dispute_opened", null, data));
            }
            case "DisputeClosed" -> {
                data.put("outcome", "won".equals(p.path("outcome").asString()) ? "won" : "lost");
                notifications.handle(new Request(id(event), text(event, "merchant_id"), null, "owners",
                        "dispute_closed", null, data));
            }
            default -> { }
        }
    }

    @KafkaListener(topics = Topics.ONBOARDING)
    void onOnboarding(String message) {
        JsonNode event = mapper.readTree(message);
        if (!"ApplicationDecided".equals(event.path("event_type").asString())) {
            return;
        }
        JsonNode p = event.path("payload");
        String merchantId = text(event, "merchant_id");
        String template = switch (p.path("status").asString()) {
            case "active" -> "kyc_approved";
            case "rejected" -> "kyc_rejected";
            case "needs_more_info" -> "kyc_needs_info";
            default -> null;
        };
        if (template == null) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("merchant_name", notifications.merchantName(merchantId));
        data.put("reason", text(p, "reason") == null ? "" : text(p, "reason"));
        data.put("resume_url", notifications.dashboardUrl() + "/onboarding");
        notifications.handle(new Request(id(event), merchantId, null, "owners", template, null, data));
    }

    @KafkaListener(topics = Topics.REPORT)
    void onReport(String message) {
        JsonNode event = mapper.readTree(message);
        if (!"ReportRunSucceeded".equals(event.path("event_type").asString())) {
            return;
        }
        JsonNode p = event.path("payload");
        if (text(p, "requested_by_email") == null) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("report_type", p.path("report_type").asString());
        data.put("download_url", p.path("download_url").asString());
        notifications.handle(new Request(id(event), text(event, "merchant_id"), p.path("requested_by_email").asString(),
                null, "report_ready", null, data));
    }

    private static String id(JsonNode event) {
        return event.path("event_id").asString();
    }

    /** Every currency we support has two decimals. */
    private static String major(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}
