package dev.cutpay.bnpl.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RollingStatsTest {

    private static final Stats PRIOR = new Stats(0.80, 600);

    @Test
    void withNoHistoryTheProviderIsJudgedByWhatIsExpectedOfIt() {
        assertThat(RollingStats.blend(PRIOR, 0, 0, 0)).isEqualTo(PRIOR);
    }

    @Test
    void aLongRecordOutweighsTheExpectation() {
        Stats stats = RollingStats.blend(PRIOR, 10, 1000, 4000);

        // 10 approvals of 1000 decided, taking 4 s: nothing of the optimistic prior is left.
        assertThat(stats.approvalRate()).isCloseTo(0.0178, within(0.001));
        assertThat(stats.p50DecisionMs()).isBetween(3900L, 4000L);
    }

    @Test
    void oneEarlyDeclineDoesNotBuryAProvider() {
        Stats stats = RollingStats.blend(PRIOR, 0, 1, 500);

        assertThat(stats.approvalRate()).isCloseTo(8.0 / 11, within(1e-9));
        assertThat(stats.p50DecisionMs()).isEqualTo(Math.round((500.0 + 6000) / 11));
    }

    @Test
    void goodNewsMovesTheFigureUpAndNeverPastOne() {
        Stats stats = RollingStats.blend(PRIOR, 50, 50, 200);

        assertThat(stats.approvalRate()).isBetween(0.9, 1.0);
        assertThat(stats.p50DecisionMs()).isLessThan(600L);
    }
}
