package dev.cutpay.billing.subscription;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Changing the plan in the middle of a period. The customer already paid for the old plan up to the end of the period,
 * so the unused part of it is credited, and the rest of the period is charged at the new plan. Both are worked out with
 * the same fraction of the period that is left, in whole seconds, and rounded half up to the minor unit.
 */
public final class Proration {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    /** One subscription line as it was or will be: a price (with its product name for the description) and a quantity. */
    public record Item(String priceId, String productName, long unitAmount, int quantity) {
        long total() {
            return Math.multiplyExact(unitAmount, (long) quantity);
        }

        boolean same(Item other) {
            return priceId.equals(other.priceId) && quantity == other.quantity;
        }
    }

    /** A credit (negative) or a charge (positive) for part of the period. */
    public record Line(String description, String priceId, long unitAmount, int quantity, long amount) {
    }

    private Proration() {
    }

    /** {@code amount * remaining / total}, rounded half up. A negative amount rounds symmetrically. */
    public static long prorate(long amount, Duration remaining, Duration total) {
        if (total.isZero() || total.isNegative()) {
            throw new IllegalArgumentException("A period has a positive length");
        }
        long left = Math.max(0, Math.min(remaining.getSeconds(), total.getSeconds()));
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(left))
                .divide(BigDecimal.valueOf(total.getSeconds()), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * The lines for changing from {@code before} to {@code after} at {@code now}, inside the period
     * {@code periodStart} to {@code periodEnd}. Items that did not change produce nothing.
     */
    public static List<Line> change(List<Item> before, List<Item> after, Instant now, Instant periodStart, Instant periodEnd) {
        Duration total = Duration.between(periodStart, periodEnd);
        Duration remaining = Duration.between(now.isBefore(periodStart) ? periodStart : now, periodEnd);
        String since = DATE.format(now);
        List<Line> lines = new ArrayList<>();
        for (Item old : before) {
            if (after.stream().noneMatch(old::same)) {
                long credit = -prorate(old.total(), remaining, total);
                lines.add(new Line("Unused time on %s × %d after %s".formatted(old.productName(), old.quantity(), since),
                        old.priceId(), old.unitAmount(), old.quantity(), credit));
            }
        }
        for (Item added : after) {
            if (before.stream().noneMatch(added::same)) {
                long charge = prorate(added.total(), remaining, total);
                lines.add(new Line("Remaining time on %s × %d after %s".formatted(added.productName(), added.quantity(), since),
                        added.priceId(), added.unitAmount(), added.quantity(), charge));
            }
        }
        return lines;
    }

    public static long net(List<Line> lines) {
        return lines.stream().mapToLong(Line::amount).sum();
    }
}
