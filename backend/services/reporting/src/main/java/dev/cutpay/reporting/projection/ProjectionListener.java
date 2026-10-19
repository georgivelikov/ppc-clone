package dev.cutpay.reporting.projection;

import dev.cutpay.reporting.config.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The live feed into the read model. */
@Component
class ProjectionListener {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProjectionListener.class);

    private final Projector projector;
    private final JsonMapper mapper;

    ProjectionListener(Projector projector, JsonMapper mapper) {
        this.projector = projector;
        this.mapper = mapper;
    }

    @KafkaListener(topics = {Topics.PAYMENT, Topics.PAYOUT, Topics.DISPUTE, Topics.LEDGER_ENTRIES})
    void onEvent(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        try {
            projector.apply(topic, mapper.readTree(message));
        } catch (RuntimeException e) {
            log.error("Could not project an event from {}: {}", topic, org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            throw e;
        }
    }
}
