package dev.cutpay.bnpl.application;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where an application stands, as an explicit transition table: anything not listed here cannot happen, so a late
 * callback can never move an application backwards.
 */
public enum ApplicationStatus {
    /** Sent to the provider, nothing back yet. */
    CREATED,
    /** The provider has not decided; a callback or a poll will tell. */
    PENDING_REVIEW,
    APPROVED,
    DECLINED,
    /** The provider could not be reached or made no sense. */
    FAILED,
    /** Nobody decided in time. */
    EXPIRED,
    /** Abandoned by the customer, or closed because the payment was settled another way. */
    CANCELED,
    /** The provider has paid Cutpay. */
    SETTLED;

    private Set<ApplicationStatus> next() {
        return switch (this) {
            case CREATED -> EnumSet.of(PENDING_REVIEW, APPROVED, DECLINED, FAILED, CANCELED);
            case PENDING_REVIEW -> EnumSet.of(APPROVED, DECLINED, EXPIRED, CANCELED);
            case APPROVED -> EnumSet.of(CANCELED, SETTLED);
            case DECLINED, FAILED, EXPIRED, CANCELED, SETTLED -> EnumSet.noneOf(ApplicationStatus.class);
        };
    }

    public boolean canMoveTo(ApplicationStatus target) {
        return next().contains(target);
    }

    /** Nothing more will happen to the application, apart from settlement of an approved one. */
    public boolean isOpen() {
        return this == CREATED || this == PENDING_REVIEW;
    }

    /** The customer is still engaged with it: it blocks starting another application for the same payment. */
    public boolean blocksAnother() {
        return this == CREATED || this == PENDING_REVIEW || this == APPROVED || this == SETTLED;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ApplicationStatus fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
