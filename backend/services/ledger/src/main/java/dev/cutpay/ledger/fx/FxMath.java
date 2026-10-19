package dev.cutpay.ledger.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * The arithmetic of a currency conversion, with no database in sight so every rule can be tested.
 *
 * <p>Amounts are minor units. A rate is how many units of the target currency one unit of the source buys, so the two
 * currencies' different numbers of decimals matter: 10.00 EUR is 1000 minor units, and 10.00 EUR at 4.28 is 42.80 PLN,
 * 4280 minor units. The exponent comes from the ISO 4217 table in the JDK, where HUF has two decimals even though a
 * forint is never split in practice; amounts are therefore in fillér, and the docs say so.
 *
 * <p>The merchant is never paid a fraction too much: what the merchant receives is rounded down, the market value is
 * rounded half-even, and the platform's markup is the difference, so the three always add up.
 */
public final class FxMath {

    /** Digits kept in a derived rate. Enough that a rate such as HUF to EUR (0.0025...) keeps six significant digits. */
    public static final int RATE_SCALE = 12;

    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    /** {@code mid} is the market value of {@code from}; {@code merchant} what the merchant gets; the rest is markup. */
    public record Conversion(long from, long mid, long merchant, long markup) {
    }

    private FxMath() {
    }

    public static int exponent(String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException("Not a currency with minor units: " + currency);
        }
        return digits;
    }

    /** The rate from one currency to another, given what one euro buys of each. */
    public static BigDecimal cross(BigDecimal eurToFrom, BigDecimal eurToTo) {
        return eurToTo.divide(eurToFrom, RATE_SCALE, RoundingMode.HALF_EVEN);
    }

    public static Conversion convert(long fromMinor, String from, String to, BigDecimal rate, int markupBps) {
        if (fromMinor <= 0) {
            throw new IllegalArgumentException("Nothing to convert");
        }
        if (markupBps < 0 || markupBps > 10_000) {
            throw new IllegalArgumentException("A markup is between 0 and 10000 basis points");
        }
        // Minor units to minor units: scale by the rate and by the difference in decimals.
        BigDecimal exactMid = BigDecimal.valueOf(fromMinor).multiply(rate)
                .movePointRight(exponent(to) - exponent(from));
        long mid = exactMid.setScale(0, RoundingMode.HALF_EVEN).longValueExact();
        BigDecimal kept = BigDecimal.valueOf(10_000 - markupBps).divide(TEN_THOUSAND);
        long merchant = exactMid.multiply(kept).setScale(0, RoundingMode.DOWN).longValueExact();
        return new Conversion(fromMinor, mid, merchant, mid - merchant);
    }
}
