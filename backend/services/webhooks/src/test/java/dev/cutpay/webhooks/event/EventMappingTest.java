package dev.cutpay.webhooks.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cutpay.webhooks.event.EventMapper.Mapped;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EventMappingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aSucceededPaymentBecomesPaymentIntentSucceededWithoutInternalFields() {
        var payload = JSON.readTree("""
                {"payment_id":"pay_1","status":"succeeded","amount":10000,"currency":"EUR",
                 "customer_email":"a@b.c","failure_code":null,"card_brand":"visa","card_type":"consumer",
                 "card_region":"eea"}""");
        Mapped mapped = EventMapper.map("PaymentSucceeded", payload).orElseThrow();
        assertThat(mapped.type()).isEqualTo("payment_intent.succeeded");
        assertThat(mapped.object()).containsEntry("id", "pay_1").containsEntry("object", "payment_intent")
                .containsEntry("amount", 10000L).containsEntry("card_brand", "visa")
                .doesNotContainKeys("card_type", "card_region", "failure_code");
    }

    @Test
    void aSucceededRefundBecomesChargeRefunded() {
        var payload = JSON.readTree("""
                {"refund_id":"re_1","payment_id":"pay_1","amount":3000,"currency":"EUR","status":"succeeded"}""");
        Mapped mapped = EventMapper.map("RefundSucceeded", payload).orElseThrow();
        assertThat(mapped.type()).isEqualTo("charge.refunded");
        assertThat(mapped.object()).containsEntry("id", "re_1").containsEntry("payment_intent", "pay_1");
    }

    @Test
    void payoutEventsMapToPayoutTypes() {
        var payload = JSON.readTree("""
                {"payout_id":"po_1","amount":9861,"fee":0,"currency":"EUR","arrival_date":"2026-10-19",
                 "kind":"scheduled","status":"paid","failure_code":null}""");
        assertThat(EventMapper.map("PayoutCreated", payload).orElseThrow().type()).isEqualTo("payout.created");
        assertThat(EventMapper.map("PayoutPaid", payload).orElseThrow().type()).isEqualTo("payout.paid");
        assertThat(EventMapper.map("PayoutFailed", payload).orElseThrow().type()).isEqualTo("payout.failed");
    }

    @Test
    void disputeEventsMapToTheChargeDisputeTypes() {
        var payload = JSON.readTree("""
                {"dispute_id":"dp_1","payment_id":"pay_1","amount":10000,"currency":"EUR","fee":1500,"reason":"fraudulent",
                 "status":"needs_response","evidence_due_by":"2026-10-27T10:00:00Z","evidence_text":"private note"}""");
        Mapped opened = EventMapper.map("DisputeOpened", payload).orElseThrow();
        assertThat(opened.type()).isEqualTo("charge.dispute.created");
        assertThat(opened.object()).containsEntry("id", "dp_1").containsEntry("object", "dispute")
                .containsEntry("payment_intent", "pay_1").containsEntry("fee", 1500L)
                .doesNotContainKey("evidence_text"); // what the merchant wrote stays out of the public event
        assertThat(EventMapper.map("DisputeEvidenceSubmitted", payload).orElseThrow().type()).isEqualTo("charge.dispute.updated");
        assertThat(EventMapper.map("DisputeClosed", payload).orElseThrow().type()).isEqualTo("charge.dispute.closed");
        assertThat(EventTypes.ALL).contains("charge.dispute.created", "charge.dispute.updated", "charge.dispute.closed");
    }

    @Test
    void everyMappedTypeIsAKnownPublicType() {
        var payment = JSON.readTree("{\"payment_id\":\"p\",\"status\":\"s\",\"currency\":\"EUR\"}");
        var refund = JSON.readTree("{\"refund_id\":\"r\",\"payment_id\":\"p\",\"status\":\"s\",\"currency\":\"EUR\"}");
        var payout = JSON.readTree("{\"payout_id\":\"o\",\"status\":\"s\",\"currency\":\"EUR\"}");
        for (String type : List.of("PaymentIntentCreated", "PaymentRequiresAction", "PaymentAuthorized", "PaymentSucceeded",
                "PaymentFailed", "PaymentCanceled")) {
            assertThat(EventTypes.ALL).contains(EventMapper.map(type, payment).orElseThrow().type());
        }
        for (String type : List.of("RefundCreated", "RefundSucceeded", "RefundFailed")) {
            assertThat(EventTypes.ALL).contains(EventMapper.map(type, refund).orElseThrow().type());
        }
        for (String type : List.of("PayoutCreated", "PayoutPaid", "PayoutFailed")) {
            assertThat(EventTypes.ALL).contains(EventMapper.map(type, payout).orElseThrow().type());
        }
    }

    @Test
    void subscriptionAndInvoiceEventsMapToTheirPublicTypes() {
        var subscription = JSON.readTree("""
                {"subscription_id":"sub_1","customer_id":"cus_1","status":"past_due","currency":"EUR",
                 "cancel_at_period_end":false,"current_period_end":"2027-02-01T00:00:00Z","latest_invoice":"in_1"}""");
        assertThat(EventMapper.map("SubscriptionCreated", subscription).orElseThrow().type())
                .isEqualTo("customer.subscription.created");
        for (String type : List.of("SubscriptionUpdated", "SubscriptionRenewed", "SubscriptionPastDue")) {
            assertThat(EventMapper.map(type, subscription).orElseThrow().type()).isEqualTo("customer.subscription.updated");
        }
        Mapped canceled = EventMapper.map("SubscriptionCanceled", subscription).orElseThrow();
        assertThat(canceled.type()).isEqualTo("customer.subscription.deleted");
        assertThat(canceled.object()).containsEntry("id", "sub_1").containsEntry("object", "subscription")
                .containsEntry("status", "past_due").containsEntry("customer", "cus_1");

        var invoice = JSON.readTree("""
                {"invoice_id":"in_1","number":"INV-0001","subscription_id":"sub_1","customer_id":"cus_1",
                 "status":"paid","currency":"EUR","total":1500,"amount_paid":1500,
                 "hosted_invoice_url":"http://localhost:5174/i/abc","failure_code":null}""");
        assertThat(EventMapper.map("InvoiceFinalized", invoice).orElseThrow().type()).isEqualTo("invoice.finalized");
        assertThat(EventMapper.map("InvoicePaymentFailed", invoice).orElseThrow().type()).isEqualTo("invoice.payment_failed");
        assertThat(EventMapper.map("InvoiceVoided", invoice).orElseThrow().type()).isEqualTo("invoice.voided");
        Mapped paid = EventMapper.map("InvoicePaid", invoice).orElseThrow();
        assertThat(paid.type()).isEqualTo("invoice.paid");
        assertThat(paid.object()).containsEntry("number", "INV-0001").containsEntry("total", 1500L)
                .containsEntry("hosted_invoice_url", "http://localhost:5174/i/abc").doesNotContainKey("failure_code");
        for (Mapped mapped : List.of(canceled, paid)) {
            assertThat(EventTypes.ALL).contains(mapped.type());
        }
    }

    @Test
    void internalOnlyEventsAreNotPublished() {
        assertThat(EventMapper.map("JournalEntryPosted", JSON.readTree("{}"))).isEmpty();
        assertThat(EventMapper.map("MerchantCreated", JSON.readTree("{}"))).isEmpty();
    }

    @Test
    void aMalformedPayloadIsRejected() {
        assertThatThrownBy(() -> EventMapper.map("PaymentSucceeded", JSON.readTree("{\"status\":\"succeeded\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subscriptionsMatchExactTypesFamiliesAndEverything() {
        assertThat(EventTypes.matches(List.of("*"), "payout.paid")).isTrue();
        assertThat(EventTypes.matches(List.of("payout.*"), "payout.paid")).isTrue();
        assertThat(EventTypes.matches(List.of("payout.paid"), "payout.paid")).isTrue();
        assertThat(EventTypes.matches(List.of("payout.paid"), "payout.failed")).isFalse();
        assertThat(EventTypes.matches(List.of("payout.*"), "payment_intent.succeeded")).isFalse();
        assertThat(EventTypes.matches(List.of("payment_intent.*", "payout.paid"), "payout.paid")).isTrue();
    }

    @Test
    void subscriptionEntriesAreValidated() {
        assertThat(EventTypes.isValidSubscription("*")).isTrue();
        assertThat(EventTypes.isValidSubscription("payout.*")).isTrue();
        assertThat(EventTypes.isValidSubscription("charge.refunded")).isTrue();
        assertThat(EventTypes.isValidSubscription("payout.exploded")).isFalse();
        assertThat(EventTypes.isValidSubscription("nothing.*")).isFalse();
        assertThat(EventTypes.isValidSubscription("payout.*,x")).isFalse();
    }

    @Test
    void paymentLinkEventsMapToPaymentLinkTypes() {
        var created = JSON.readTree("""
                {"link_id":"plink_1","slug":"AbCdEfGhJk","url":"http://x/l/AbCdEfGhJk","type":"fixed",
                 "title":"Table 4","currency":"EUR","amount":1500}""");
        Mapped c = EventMapper.map("PaymentLinkCreated", created).orElseThrow();
        assertThat(c.type()).isEqualTo("payment_link.created");
        assertThat(c.object()).containsEntry("id", "plink_1").containsEntry("object", "payment_link")
                .containsEntry("amount", 1500L).containsEntry("active", true);

        var deactivated = JSON.readTree("""
                {"link_id":"plink_1","slug":"AbCdEfGhJk","reason":"max_uses_reached"}""");
        Mapped d = EventMapper.map("PaymentLinkDeactivated", deactivated).orElseThrow();
        assertThat(d.type()).isEqualTo("payment_link.deactivated");
        assertThat(d.object()).containsEntry("active", false).containsEntry("reason", "max_uses_reached");
    }
}
