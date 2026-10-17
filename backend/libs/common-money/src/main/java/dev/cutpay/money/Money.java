package dev.cutpay.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * An amount of money in <b>minor units</b> (cents, stotinki, grosze) of an ISO-4217 currency.
 *
 * <p>Never use {@code double} for money: 0.1 + 0.2 != 0.3 in binary floating point.
 * Storing whole minor units in a {@code long} keeps arithmetic exact; rounding happens
 * only in {@link #multiply(BigDecimal, RoundingMode)}, at the single point where a
 * fractional amount (e.g. a percentage fee) is converted back to minor units.
 */
public record Money(long minor, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Pseudo-currency not supported: " + currency);
        }
    }

    public static Money ofMinor(long minor, String currencyCode) {
        return new Money(minor, currency(currencyCode));
    }

    /** Parses a major-unit decimal such as {@code 12.34}; rejects values with too many decimals. */
    public static Money of(BigDecimal major, String currencyCode) {
        Currency currency = currency(currencyCode);
        try {
            long minor = major.movePointRight(currency.getDefaultFractionDigits()).longValueExact();
            return new Money(minor, currency);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount %s has more decimals than %s allows".formatted(major.toPlainString(), currencyCode), e);
        }
    }

    public static Money zero(String currencyCode) {
        return ofMinor(0, currencyCode);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(minor, factor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    /** Multiplies by a decimal factor (e.g. a fee rate of 0.0129) and rounds to minor units. */
    public Money multiply(BigDecimal factor, RoundingMode rounding) {
        BigDecimal result = BigDecimal.valueOf(minor).multiply(factor).setScale(0, rounding);
        return new Money(result.longValueExact(), currency);
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    /** The amount in major units, e.g. {@code 12.34} for 1234 EUR minor units. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, currency.getDefaultFractionDigits());
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    @Override
    public String toString() {
        return toMajor().toPlainString() + " " + currencyCode();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    private static Currency currency(String code) {
        Objects.requireNonNull(code, "currencyCode");
        return Currency.getInstance(code.toUpperCase(Locale.ROOT));
    }
}
