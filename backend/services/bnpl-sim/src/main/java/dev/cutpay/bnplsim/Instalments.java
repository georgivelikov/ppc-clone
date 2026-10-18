package dev.cutpay.bnplsim;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** The arithmetic of splitting a price into payments. All amounts are minor units. */
final class Instalments {

    private Instalments() {
    }

    /** {@code total} split into {@code count} payments that add up to it; the first ones take the extra cents. */
    static List<Long> equal(long total, int count) {
        long base = total / count;
        long extra = total % count;
        List<Long> parts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            parts.add(base + (i < extra ? 1 : 0));
        }
        return parts;
    }

    /**
     * Equal monthly payments of a loan at {@code aprPercent} a year (a plain monthly rate of a twelfth), each rounded half
     * up. The total payable is therefore the payment times the months, a few cents off the exact figure.
     */
    static List<Long> annuity(long principal, int months, double aprPercent) {
        if (aprPercent == 0) {
            return equal(principal, months);
        }
        BigDecimal rate = BigDecimal.valueOf(aprPercent).divide(BigDecimal.valueOf(1200), MathContext.DECIMAL64);
        BigDecimal growth = BigDecimal.ONE.add(rate).pow(months, MathContext.DECIMAL64);
        BigDecimal payment = BigDecimal.valueOf(principal).multiply(rate).multiply(growth)
                .divide(growth.subtract(BigDecimal.ONE), MathContext.DECIMAL64);
        long each = payment.setScale(0, RoundingMode.HALF_UP).longValueExact();
        List<Long> parts = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            parts.add(each);
        }
        return parts;
    }

    /** A percentage of an amount in basis points, rounded half up. */
    static long fee(long amount, int bps) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    static long sum(List<Long> parts) {
        return parts.stream().mapToLong(Long::longValue).sum();
    }
}
