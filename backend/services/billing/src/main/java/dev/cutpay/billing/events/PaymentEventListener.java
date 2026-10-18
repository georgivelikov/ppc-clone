package dev.cutpay.billing.events;

import dev.cutpay.billing.config.Topics;
import dev.cutpay.billing.invoice.InvoiceService;
import dev.cutpay.events.InboxGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settles invoices from the outcome of the payments made for them. A payment made for an invoice carries its id in
 * the metadata ({@code invoice_id}). The inbox makes a redelivered event a no-op, and the invoice itself refuses to be
 * paid twice, so the same payment can safely arrive here and also be handled where it was started.
 */
@Component
class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final InvoiceService invoices;
    private final InboxGuard inbox;
    private final TransactionTemplate tx;
    private final JsonMapper mapper;

    PaymentEventListener(InvoiceService invoices, InboxGuard inbox, TransactionTemplate tx, JsonMapper mapper) {
        this.invoices = invoices;
        this.inbox = inbox;
        this.tx = tx;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.PAYMENT)
    void onPaymentEvent(String message) {
        JsonNode event = mapper.readTree(message);
        String type = event.path("event_type").asString();
        if (!"PaymentSucceeded".equals(type) && !"PaymentFailed".equals(type)) {
            return;
        }
        JsonNode payload = event.path("payload");
        String invoiceId = payload.path("metadata").path("invoice_id").asString("");
        if (invoiceId.isEmpty()) {
            return;
        }
        String paymentId = payload.path("payment_id").asString();
        String eventId = event.path("event_id").asString();
        boolean firstTime = Boolean.TRUE.equals(tx.execute(status -> inbox.firstTime(eventId)));
        if (!firstTime) {
            return;
        }
        if ("PaymentSucceeded".equals(type)) {
            invoices.onPaymentSucceeded(invoiceId, paymentId);
        } else if (payload.path("off_session").asBoolean(false)) {
            // A customer who fails to pay on the hosted page can simply try again, so only off-session failures
            // count as a failed attempt.
            invoices.onPaymentFailed(invoiceId, paymentId, payload.path("failure_code").asString(null));
        } else {
            log.debug("Ignoring on-session failure {} for invoice {}", paymentId, invoiceId);
        }
    }
}
