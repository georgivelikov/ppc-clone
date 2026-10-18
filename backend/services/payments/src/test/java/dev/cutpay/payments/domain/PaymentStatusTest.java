package dev.cutpay.payments.domain;

import static dev.cutpay.payments.domain.PaymentStatus.CANCELED;
import static dev.cutpay.payments.domain.PaymentStatus.FAILED;
import static dev.cutpay.payments.domain.PaymentStatus.PARTIALLY_REFUNDED;
import static dev.cutpay.payments.domain.PaymentStatus.PROCESSING;
import static dev.cutpay.payments.domain.PaymentStatus.REFUNDED;
import static dev.cutpay.payments.domain.PaymentStatus.REQUIRES_ACTION;
import static dev.cutpay.payments.domain.PaymentStatus.REQUIRES_CAPTURE;
import static dev.cutpay.payments.domain.PaymentStatus.REQUIRES_PAYMENT_METHOD;
import static dev.cutpay.payments.domain.PaymentStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cutpay.money.Money;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            REQUIRES_PAYMENT_METHOD, EnumSet.of(PROCESSING, CANCELED),
            PROCESSING, EnumSet.of(SUCCEEDED, REQUIRES_ACTION, REQUIRES_CAPTURE, FAILED),
            REQUIRES_CAPTURE, EnumSet.of(SUCCEEDED, CANCELED),
            REQUIRES_ACTION, EnumSet.of(PROCESSING, FAILED, CANCELED),
            SUCCEEDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    @Test
    void everyPairOfStatesIsCheckedAgainstTheTable() {
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertThat(from.canMoveTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    void illegalTransitionsAreRejectedWithADomainError() {
        assertThatThrownBy(() -> SUCCEEDED.requireCanMoveTo(PROCESSING))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("succeeded")
                .hasMessageContaining("processing");
    }

    @Test
    void terminalStatesHaveNoExit() {
        for (PaymentStatus terminal : EnumSet.of(FAILED, CANCELED, REFUNDED)) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertThat(terminal.canMoveTo(to)).isFalse();
            }
        }
    }

    @Test
    void wireNamesRoundTrip() {
        assertThat(REQUIRES_PAYMENT_METHOD.wire()).isEqualTo("requires_payment_method");
        assertThat(PaymentStatus.fromWire("partially_refunded")).isEqualTo(PARTIALLY_REFUNDED);
    }

    @Test
    void anIntentFollowsTheHappyPathAndTheRefundRules() {
        var intent = new PaymentIntent("pay_1", "secret", "mer_1", "Cafe", false, Money.ofMinor(1000, "EUR"), null,
                null, null, null);
        intent.startProcessing("a@b.co");
        intent.succeed();
        assertThat(intent.refundable()).isEqualTo(1000);

        intent.reserveRefund(500);
        intent.applyRefundedTotal(500);
        assertThat(intent.getStatus()).isEqualTo(PARTIALLY_REFUNDED);

        intent.reserveRefund(500);
        intent.applyRefundedTotal(1000);
        assertThat(intent.getStatus()).isEqualTo(REFUNDED);
        assertThat(intent.refundable()).isZero();
    }

    private static PaymentIntent manualIntent(long amount) {
        return new PaymentIntent("pay_1", "secret", "mer_1", "Hotel", false, Money.ofMinor(amount, "EUR"), null,
                null, null, null, PaymentIntent.CHANNEL_ONLINE, PaymentIntent.CAPTURE_MANUAL);
    }

    @Test
    void aPartialCaptureShrinksThePaymentToWhatWasTakenAndTheRestIsReleased() {
        var intent = manualIntent(20_000);
        intent.startProcessing("guest@example.com");
        intent.authorize(java.time.Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(intent.getStatus()).isEqualTo(REQUIRES_CAPTURE);
        assertThat(intent.getAmountAuthorized()).isEqualTo(20_000);

        intent.capture(15_000);

        assertThat(intent.getStatus()).isEqualTo(SUCCEEDED);
        assertThat(intent.getAmount()).isEqualTo(15_000);
        assertThat(intent.getAmountAuthorized()).isEqualTo(20_000);
        assertThat(intent.refundable()).isEqualTo(15_000);
        assertThat(intent.getCaptureBefore()).isNull();
    }

    @Test
    void youCannotCaptureMoreThanWasHeldOrNothing() {
        var intent = manualIntent(20_000);
        intent.startProcessing(null);
        intent.authorize(java.time.Instant.parse("2026-11-01T00:00:00Z"));
        assertThatThrownBy(() -> intent.capture(20_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> intent.capture(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(intent.getStatus()).isEqualTo(REQUIRES_CAPTURE);
    }

    @Test
    void anExpiredAuthorisationIsCanceledWithAReasonAndCannotBeCapturedAfterwards() {
        var intent = manualIntent(20_000);
        intent.startProcessing(null);
        intent.authorize(java.time.Instant.parse("2026-11-01T00:00:00Z"));
        intent.expire();
        assertThat(intent.getStatus()).isEqualTo(CANCELED);
        assertThat(intent.getFailureCode()).isEqualTo("authorization_expired");
        assertThatThrownBy(() -> intent.capture(100)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void aFailedPaymentCannotBeRetried() {
        var intent = new PaymentIntent("pay_1", "secret", "mer_1", "Cafe", false, Money.ofMinor(1000, "EUR"), null,
                null, null, null);
        intent.startProcessing(null);
        intent.fail("card_declined", "Your card was declined.");
        assertThatThrownBy(() -> intent.startProcessing(null)).isInstanceOf(IllegalTransitionException.class);
    }
}
