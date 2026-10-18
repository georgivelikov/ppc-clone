package dev.cutpay.payments.domain;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The payment lifecycle as an explicit transition table. Anything not listed here is illegal and
 * {@link #requireCanMoveTo} rejects it, so no code path can skip a state by accident.
 */
public enum PaymentStatus {
    REQUIRES_PAYMENT_METHOD,
    PROCESSING,
    REQUIRES_ACTION,
    /** Authorised and held on the card, waiting for the merchant to capture or release it. */
    REQUIRES_CAPTURE,
    SUCCEEDED,
    FAILED,
    CANCELED,
    PARTIALLY_REFUNDED,
    REFUNDED;

    private Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case REQUIRES_PAYMENT_METHOD -> EnumSet.of(PROCESSING, CANCELED);
            case PROCESSING -> EnumSet.of(SUCCEEDED, REQUIRES_ACTION, REQUIRES_CAPTURE, FAILED);
            case REQUIRES_CAPTURE -> EnumSet.of(SUCCEEDED, CANCELED);
            case REQUIRES_ACTION -> EnumSet.of(PROCESSING, FAILED, CANCELED);
            case SUCCEEDED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
            case PARTIALLY_REFUNDED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
            case FAILED, CANCELED, REFUNDED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canMoveTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public void requireCanMoveTo(PaymentStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalTransitionException(this, next);
        }
    }

    /** The lowercase name used in the API and events. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PaymentStatus fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
