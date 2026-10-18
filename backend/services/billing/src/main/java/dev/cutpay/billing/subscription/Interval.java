package dev.cutpay.billing.subscription;

import java.util.Locale;

/** The unit a recurring price is billed in. */
public enum Interval {
    DAY, WEEK, MONTH, YEAR;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Interval fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
