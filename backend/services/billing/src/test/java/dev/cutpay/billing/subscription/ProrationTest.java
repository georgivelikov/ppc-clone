package dev.cutpay.billing.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cutpay.billing.subscription.Proration.Item;
import dev.cutpay.billing.subscription.Proration.Line;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan changes in the middle of a period, with fixed dates so every number can be checked by hand. */
class ProrationTest {

    private static final Instant START = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2027-02-01T00:00:00Z"); // 31 days

    private static final Item BASIC = new Item("price_basic", "Basic", 1000, 1);
    private static final Item PRO = new Item("price_pro", "Pro", 3000, 1);

    @Test
    void halfWayThroughThePeriodHalfOfEachPlanIsCreditedAndCharged() {
        Instant halfway = Instant.parse("2027-01-16T12:00:00Z"); // exactly 15.5 of 31 days left
        List<Line> lines = Proration.change(List.of(BASIC), List.of(PRO), halfway, START, END);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).amount()).isEqualTo(-500);
        assertThat(lines.get(0).description()).startsWith("Unused time on Basic × 1 after 16 Jan 2027");
        assertThat(lines.get(1).amount()).isEqualTo(1500);
        assertThat(lines.get(1).description()).startsWith("Remaining time on Pro × 1");
        assertThat(Proration.net(lines)).isEqualTo(1000);
    }

    @Test
    void changingOnTheFirstDayChargesTheWholeDifference() {
        List<Line> lines = Proration.change(List.of(BASIC), List.of(PRO), START, START, END);
        assertThat(Proration.net(lines)).isEqualTo(2000);
    }

    @Test
    void changingAtTheEndOfThePeriodCostsNothing() {
        List<Line> lines = Proration.change(List.of(BASIC), List.of(PRO), END, START, END);
        assertThat(lines).extracting(Line::amount).containsExactly(0L, 0L);
        assertThat(Proration.net(lines)).isZero();
    }

    @Test
    void aDowngradeProducesACreditThatIsMoreThanTheCharge() {
        Instant halfway = Instant.parse("2027-01-16T12:00:00Z");
        List<Line> lines = Proration.change(List.of(PRO), List.of(BASIC), halfway, START, END);
        assertThat(Proration.net(lines)).isEqualTo(-1000);
    }

    @Test
    void secondsAreRoundedHalfUpToTheMinorUnitForCreditsAndChargesAlike() {
        // 999 for half the period is 499.5: a charge rounds up to 500 and a credit mirrors it.
        Item odd = new Item("price_odd", "Odd", 999, 1);
        Instant halfway = Instant.parse("2027-01-16T12:00:00Z");
        assertThat(Proration.change(List.of(), List.of(odd), halfway, START, END).get(0).amount()).isEqualTo(500);
        assertThat(Proration.change(List.of(odd), List.of(), halfway, START, END).get(0).amount()).isEqualTo(-500);
    }

    @Test
    void anUnchangedItemProducesNothingAndAnAddedItemOnlyACharge() {
        Item addon = new Item("price_addon", "Support", 500, 1);
        Instant halfway = Instant.parse("2027-01-16T12:00:00Z");
        List<Line> lines = Proration.change(List.of(BASIC), List.of(BASIC, addon), halfway, START, END);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).amount()).isEqualTo(250);
    }

    @Test
    void changingTheNumberOfSeatsCreditsTheOldCountAndChargesTheNewOne() {
        Item three = new Item("price_seat", "Seat", 900, 3);
        Item five = new Item("price_seat", "Seat", 900, 5);
        // 10 of 30 days are left in a 30-day period: a third of 2700 is credited, a third of 4500 charged.
        Instant start = Instant.parse("2027-04-01T00:00:00Z");
        Instant end = Instant.parse("2027-05-01T00:00:00Z");
        Instant now = Instant.parse("2027-04-21T00:00:00Z");
        List<Line> lines = Proration.change(List.of(three), List.of(five), now, start, end);
        assertThat(lines).extracting(Line::amount).containsExactly(-900L, 1500L);
    }

    @Test
    void aMonthOf28DaysAndOneOf31GiveDifferentFractionsForTheSameNumberOfDaysLeft() {
        long in31DayMonth = Proration.prorate(3100, Duration.ofDays(10), Duration.ofDays(31));
        long in28DayMonth = Proration.prorate(3100, Duration.ofDays(10), Duration.ofDays(28));
        assertThat(in31DayMonth).isEqualTo(1000);
        assertThat(in28DayMonth).isEqualTo(1107);
    }

    @Test
    void timeBeforeThePeriodStartedOrAfterItEndedIsClamped() {
        assertThat(Proration.prorate(1000, Duration.ofDays(40), Duration.ofDays(31))).isEqualTo(1000);
        assertThat(Proration.prorate(1000, Duration.ofDays(-3), Duration.ofDays(31))).isZero();
    }

    @Test
    void aPeriodWithoutLengthIsRefused() {
        assertThatThrownBy(() -> Proration.prorate(1000, Duration.ofDays(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
