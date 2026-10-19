package dev.cutpay.identity.security;

import dev.cutpay.events.OutboxWriter;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks the notifications service to send an email by publishing {@code EmailRequested}, in the same transaction as
 * the change that caused it. Identity never talks to a mail server itself.
 */
@Component
public class Mailer {

    public static final String TOPIC = "notification.events";

    private final OutboxWriter outbox;
    private final String dashboardUrl;

    public Mailer(OutboxWriter outbox, @Value("${cutpay.identity.dashboard-url:http://localhost:5173}") String dashboardUrl) {
        this.outbox = outbox;
        this.dashboardUrl = dashboardUrl;
    }

    public String dashboardUrl() {
        return dashboardUrl;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void send(String merchantId, String to, String template, Map<String, String> data) {
        outbox.write(TOPIC, "EmailRequested", to, merchantId, null,
                Map.of("to", to, "template", template, "data", data));
    }
}
