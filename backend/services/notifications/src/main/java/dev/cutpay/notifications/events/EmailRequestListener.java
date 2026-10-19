package dev.cutpay.notifications.events;

import dev.cutpay.notifications.config.Topics;
import dev.cutpay.notifications.log.NotificationService;
import dev.cutpay.notifications.log.NotificationService.Request;
import java.util.HashMap;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The general way to ask for an email: publish {@code EmailRequested} on {@code notification.events} with a template
 * name, either a recipient address ({@code to}) or a business and an audience, and the values the template uses.
 */
@Component
class EmailRequestListener {

    private final NotificationService notifications;
    private final JsonMapper mapper;

    EmailRequestListener(NotificationService notifications, JsonMapper mapper) {
        this.notifications = notifications;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.NOTIFICATION)
    void onRequest(String message) {
        JsonNode event = mapper.readTree(message);
        if (!"EmailRequested".equals(event.path("event_type").asString())) {
            return;
        }
        JsonNode p = event.path("payload");
        Map<String, Object> data = new HashMap<>();
        p.path("data").properties().forEach(e ->
                data.put(e.getKey(), e.getValue().isString() ? e.getValue().asString() : e.getValue().toString()));
        notifications.handle(new Request(event.path("event_id").asString(), text(event, "merchant_id"), text(p, "to"),
                text(p, "audience"), p.path("template").asString(), text(p, "locale"), data));
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
