package dev.cutpay.billing.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PeriodsTest {

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void aSubscriptionStartedOnThe31stDoesNotDriftToThe28th() {
        Instant anchor = at("2027-01-31T10:00:00Z");
        assertThat(Periods.boundary(anchor, Interval.MONTH, 1, 0)).isEqualTo(anchor);
        assertThat(Periods.boundary(anchor, Interval.MONTH, 1, 1)).isEqualTo(at("2027-02-28T10:00:00Z"));
        // Counted from the anchor, March is the 31st again instead of following the 28th of February.
        assertThat(Periods.boundary(anchor, Interval.MONTH, 1, 2)).isEqualTo(at("2027-03-31T10:00:00Z"));
        assertThat(Periods.boundary(anchor, Interval.MONTH, 1, 3)).isEqualTo(at("2027-04-30T10:00:00Z"));
    }

    @Test
    void leapDaysAreRespected() {
        assertThat(Periods.boundary(at("2028-01-31T00:00:00Z"), Interval.MONTH, 1, 1)).isEqualTo(at("2028-02-29T00:00:00Z"));
        assertThat(Periods.boundary(at("2028-02-29T00:00:00Z"), Interval.YEAR, 1, 1)).isEqualTo(at("2029-02-28T00:00:00Z"));
        assertThat(Periods.boundary(at("2028-02-29T00:00:00Z"), Interval.YEAR, 1, 4)).isEqualTo(at("2032-02-29T00:00:00Z"));
    }

    @Test
    void everyIntervalMovesByItsCount() {
        Instant anchor = at("2027-01-01T00:00:00Z");
        assertThat(Periods.boundary(anchor, Interval.DAY, 10, 1)).isEqualTo(at("2027-01-11T00:00:00Z"));
        assertThat(Periods.boundary(anchor, Interval.WEEK, 2, 1)).isEqualTo(at("2027-01-15T00:00:00Z"));
        assertThat(Periods.boundary(anchor, Interval.MONTH, 3, 1)).isEqualTo(at("2027-04-01T00:00:00Z"));
        assertThat(Periods.boundary(anchor, Interval.YEAR, 1, 1)).isEqualTo(at("2028-01-01T00:00:00Z"));
        // The first paid period of a subscription that had a trial starts at the anchor itself.
        assertThat(Periods.boundary(anchor, Interval.MONTH, 1, 0)).isEqualTo(anchor);
    }

    @Test
    void recurringRevenueIsNormalisedToAMonth() {
        assertThat(Periods.monthlyAmount(1200, Interval.MONTH, 1)).isEqualTo(1200);
        assertThat(Periods.monthlyAmount(3600, Interval.MONTH, 3)).isEqualTo(1200);
        assertThat(Periods.monthlyAmount(12000, Interval.YEAR, 1)).isEqualTo(1000);
        assertThat(Periods.monthlyAmount(1000, Interval.WEEK, 1)).isEqualTo(4333);
        assertThat(Periods.monthlyAmount(100, Interval.DAY, 1)).isEqualTo(3042);
    }
}
