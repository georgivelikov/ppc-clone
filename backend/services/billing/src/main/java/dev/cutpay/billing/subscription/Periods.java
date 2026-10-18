package dev.cutpay.billing.subscription;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Billing periods are counted from the first day, the anchor: period k starts at the anchor plus k intervals. Adding
 * to the anchor every time, instead of to the previous period end, is what stops a subscription that started on the
 * 31st from drifting to the 28th after February.
 */
public final class Periods {

    private Periods() {
    }

    /** The start of period {@code index} (the end of period {@code index - 1}). */
    public static Instant boundary(Instant anchor, Interval interval, int count, int index) {
        ZonedDateTime start = anchor.atZone(ZoneOffset.UTC);
        long steps = (long) index * count;
        ZonedDateTime moved = switch (interval) {
            case DAY -> start.plusDays(steps);
            case WEEK -> start.plusWeeks(steps);
            case MONTH -> start.plusMonths(steps);
            case YEAR -> start.plusYears(steps);
        };
        return moved.toInstant();
    }

    /**
     * What one period of the price is worth per month, for the monthly recurring revenue figure. A year is twelve
     * months, a week 52/12 of a month and a day 365/12 of a month, rounded to the nearest minor unit.
     */
    public static long monthlyAmount(long amount, Interval interval, int count) {
        double perMonth = switch (interval) {
            case DAY -> amount * (365.0 / 12) / count;
            case WEEK -> amount * (52.0 / 12) / count;
            case MONTH -> (double) amount / count;
            case YEAR -> (double) amount / (12L * count);
        };
        return Math.round(perMonth);
    }
}
