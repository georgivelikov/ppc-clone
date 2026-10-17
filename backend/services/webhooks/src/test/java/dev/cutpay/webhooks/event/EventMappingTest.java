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
    void everyMappedTypeIsAKnownPublicType() {
        var payment = JSON.readTree("{\"payment_id\":\"p\",\"status\":\"s\",\"currency\":\"EUR\"}");
        var refund = JSON.readTree("{\"refund_id\":\"r\",\"payment_id\":\"p\",\"status\":\"s\",\"currency\":\"EUR\"}");
        var payout = JSON.readTree("{\"payout_id\":\"o\",\"status\":\"s\",\"currency\":\"EUR\"}");
        for (String type : List.of("PaymentIntentCreated", "PaymentRequiresAction", "PaymentSucceeded",
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
}
