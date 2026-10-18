package dev.cutpay.billing.events;

import dev.cutpay.billing.config.BillingProperties;
import dev.cutpay.billing.config.Topics;
import dev.cutpay.billing.invoice.InvoiceRepository.Invoice;
import dev.cutpay.billing.subscription.SubscriptionRepository.Subscription;
import dev.cutpay.events.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Writes the subscription and invoice events to the outbox, in the transaction of the change they describe. */
@Component
public class BillingEvents {

    private final OutboxWriter outbox;
    private final BillingProperties properties;

    BillingEvents(OutboxWriter outbox, BillingProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    public String hostedUrl(Invoice invoice) {
        return properties.checkoutUrl() + "/i/" + invoice.hostedToken();
    }

    public void subscription(String eventType, Subscription s) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subscription_id", s.id());
        payload.put("customer_id", s.customerId());
        payload.put("status", s.status());
        payload.put("currency", s.currency());
        payload.put("cancel_at_period_end", s.cancelAtPeriodEnd());
        payload.put("current_period_end", s.currentPeriodEnd().toString());
        payload.put("latest_invoice", s.latestInvoiceId());
        outbox.write(Topics.SUBSCRIPTION, eventType, s.id(), s.merchantId(), s.livemode(), payload);
    }

    public void invoice(String eventType, Invoice i) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoice_id", i.id());
        payload.put("number", i.number());
        payload.put("subscription_id", i.subscriptionId());
        payload.put("customer_id", i.customerId());
        payload.put("status", i.status());
        payload.put("currency", i.currency());
        payload.put("total", i.total());
        payload.put("amount_paid", i.amountPaid());
        payload.put("hosted_invoice_url", hostedUrl(i));
        payload.put("failure_code", i.failureCode());
        outbox.write(Topics.INVOICE, eventType, i.id(), i.merchantId(), i.livemode(), payload);
    }
}
