package dev.cutpay.bnpl.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Providers write amounts as decimals in euros; Cutpay keeps minor units. */
public final class Money {

    private Money() {
    }

    /** "250.00", 250, 250.5 or a BigDecimal as 25000, rounding half up to cents. */
    public static long toMinor(Object decimal) {
        BigDecimal value = decimal instanceof BigDecimal big ? big : new BigDecimal(String.valueOf(decimal));
        return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /** 25000 as "250.00". */
    public static String toDecimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    /** 25000 as the number 250.00, for providers that want a JSON number in euros. */
    public static BigDecimal toDecimalNumber(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    /** "€250.00" for EUR, otherwise "250.00 USD". For messages a person reads. */
    public static String display(long minor, String currency) {
        return "EUR".equals(currency) ? "€" + toDecimal(minor) : toDecimal(minor) + " " + currency;
    }
}
