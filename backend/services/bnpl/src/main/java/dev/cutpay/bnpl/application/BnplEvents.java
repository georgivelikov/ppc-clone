package dev.cutpay.bnpl.application;

import dev.cutpay.bnpl.application.ApplicationRepository.Application;
import dev.cutpay.bnpl.config.Topics;
import dev.cutpay.events.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Writes the events about applications to the outbox, in the transaction of the change they describe. Payments listens
 * for {@code BnplApproved} to settle the payment; the ledger and the dashboard follow the rest.
 */
@Component
public class BnplEvents {

    private final OutboxWriter outbox;

    BnplEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void created(Application a) {
        write("BnplApplicationCreated", a);
    }

    public void approved(Application a) {
        write("BnplApproved", a);
    }

    public void declined(Application a) {
        write("BnplDeclined", a);
    }

    /** The provider did not answer in time. */
    public void timedOut(Application a) {
        write("BnplProviderTimedOut", a);
    }

    /** The provider was down or sent something unreadable. */
    public void providerFailed(Application a) {
        write("BnplProviderFailed", a);
    }

    public void expired(Application a) {
        write("BnplExpired", a);
    }

    public void canceled(Application a) {
        write("BnplCanceled", a);
    }

    private void write(String eventType, Application a) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("application_id", a.id());
        payload.put("payment_id", a.paymentId());
        payload.put("provider", a.provider());
        payload.put("plan", a.plan());
        payload.put("status", a.status().wire());
        payload.put("amount", a.amount());
        payload.put("currency", a.currency());
        payload.put("instalment_count", a.instalmentCount());
        payload.put("total_payable", a.totalPayable());
        payload.put("merchant_fee", a.merchantFee());
        payload.put("provider_ref", a.providerRef());
        payload.put("decline_reason", a.declineReason());
        payload.put("decision_ms", a.decisionMs());
        outbox.write(Topics.BNPL, eventType, a.id(), a.merchantId(), a.livemode(), payload);
    }
}
