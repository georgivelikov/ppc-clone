package dev.cutpay.payments.payment;

import dev.cutpay.payments.config.KafkaTopicsConfig;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hears about payments settled by <em>other</em> instances. Every instance needs every event (the watcher
 * may be connected to any of them), so each one reads the topic under its own random group id and only
 * from now on: this is a live feed, not history.
 */
@Component
class PaymentStreamListener {

    private final PaymentStreams streams;
    private final JsonMapper mapper;

    PaymentStreamListener(PaymentStreams streams, JsonMapper mapper) {
        this.streams = streams;
        this.mapper = mapper;
    }

    @KafkaListener(topics = KafkaTopicsConfig.PAYMENT_TOPIC,
            groupId = "#{T(java.util.UUID).randomUUID().toString()}",
            properties = "auto.offset.reset=latest")
    void onEvent(String message) {
        JsonNode event = mapper.readTree(message);
        JsonNode payload = event.path("payload");
        if (payload.path("payment_id").isMissingNode() || payload.path("status").isMissingNode()) {
            return;
        }
        JsonNode failure = payload.path("failure_code");
        streams.publish(payload.path("payment_id").asString(), payload.path("status").asString(),
                failure.isMissingNode() || failure.isNull() ? null : failure.asString());
    }
}
