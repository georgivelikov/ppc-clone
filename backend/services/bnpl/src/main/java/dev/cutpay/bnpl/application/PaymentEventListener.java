package dev.cutpay.bnpl.application;

import dev.cutpay.bnpl.config.Topics;
import dev.cutpay.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listens to what payments says about payments that have an application. When the customer paid another way while their
 * application was being decided, payments cannot use the approval and says so; the application is then closed, at the
 * provider as well, so that nobody collects instalments for a payment that was already paid. Closing is idempotent (a
 * second delivery finds the application closed and does nothing), so no inbox is needed.
 */
@Component
class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final ApplicationService applications;
    private final JsonMapper mapper;

    PaymentEventListener(ApplicationService applications, JsonMapper mapper) {
        this.applications = applications;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.PAYMENT)
    void onPaymentEvent(String message) {
        JsonNode event = mapper.readTree(message);
        if (!"BnplPaymentRejected".equals(event.path("event_type").asString())) {
            return;
        }
        JsonNode payload = event.path("payload");
        String applicationId = payload.path("application_id").asString();
        log.info("Payment {} was settled another way, closing application {}", payload.path("payment_id").asString(), applicationId);
        try {
            applications.cancelBecausePaidElsewhere(applicationId);
        } catch (ApiException e) {
            // Already closed, or never existed: nothing left to do.
            log.info("Application {} was not closed: {}", applicationId, e.getMessage());
        }
    }
}
