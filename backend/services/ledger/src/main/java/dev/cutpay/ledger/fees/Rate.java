package dev.cutpay.ledger.fees;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A percentage plus a fixed amount. The percentage is in basis points (129 = 1.29%) and the fixed part
 * in minor units. Rounding happens exactly once, here, with banker's rounding (HALF_EVEN), so the fee
 * never depends on the order of operations.
 */
public record Rate(int bps, long fixedMinor) {

    public Rate {
        if (bps < 0 || fixedMinor < 0) {
            throw new IllegalArgumentException("A rate cannot be negative");
        }
    }

    public long apply(long amount) {
        long percentage = BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_EVEN)
                .longValueExact();
        return Math.addExact(percentage, fixedMinor);
    }
}
