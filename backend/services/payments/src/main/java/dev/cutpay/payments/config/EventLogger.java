package dev.cutpay.payments.config;

import dev.cutpay.events.InboxGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only consumer of payment events for now (the ledger arrives in phase 2). It logs every event once;
 * the inbox makes a redelivered event a no-op.
 */
@Component
class EventLogger {

    private static final Logger log = LoggerFactory.getLogger(EventLogger.class);

    private final InboxGuard inbox;
    private final JsonMapper mapper;

    EventLogger(InboxGuard inbox, JsonMapper mapper) {
        this.inbox = inbox;
        this.mapper = mapper;
    }

    @KafkaListener(topics = {KafkaTopicsConfig.PAYMENT_TOPIC, KafkaTopicsConfig.REFUND_TOPIC})
    @Transactional
    void onEvent(String message) {
        JsonNode event = mapper.readTree(message);
        String eventId = event.path("event_id").asString();
        if (!inbox.firstTime(eventId)) {
            log.debug("Skipping duplicate event {}", eventId);
            return;
        }
        log.info("event {} {} aggregate={}", eventId, event.path("event_type").asString(),
                event.path("aggregate_id").asString());
    }
}
