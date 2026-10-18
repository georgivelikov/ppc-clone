package dev.cutpay.bnpl.routing;

import static dev.cutpay.bnpl.config.Sql.ts;

import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How a provider has really been doing, from the applications it decided in the last week: the share it approved and how
 * long the middle one took. With little history the figures lean on the priors, as if the provider had already been
 * seen {@value #PRIOR_WEIGHT} times behaving as expected, so one early decline does not bury a provider and a provider
 * with a long record is judged by it.
 *
 * <p>This is a plain query over the applications table. A windowed aggregation over {@code bnpl.events} would give the
 * same numbers; the table is already there, and it is what the analytics page reads too.
 */
@Component
public class RollingStats implements StatsSource {

    static final int PRIOR_WEIGHT = 10;
    static final Duration WINDOW = Duration.ofDays(7);

    private final PriorStats priors = new PriorStats();
    private final JdbcClient jdbc;
    private final Clock clock;

    RollingStats(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Stats stats(String provider) {
        var row = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status IN ('approved', 'settled')) AS approved,
                               count(*) AS decided,
                               COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY decision_ms), 0) AS p50
                          FROM bnpl_applications
                         WHERE provider = :provider AND created_at >= :since AND decision_ms IS NOT NULL
                           AND status IN ('approved', 'settled', 'declined')
                        """)
                .param("provider", provider).param("since", ts(clock.instant().minus(WINDOW)))
                .query((rs, n) -> new long[] {rs.getLong("approved"), rs.getLong("decided"), Math.round(rs.getDouble("p50"))})
                .single();
        return blend(priors.stats(provider), row[0], row[1], row[2]);
    }

    /** The priors counted as {@code PRIOR_WEIGHT} observations, next to the {@code decided} real ones. */
    static Stats blend(Stats prior, long approved, long decided, long measuredP50) {
        double rate = (approved + prior.approvalRate() * PRIOR_WEIGHT) / (decided + PRIOR_WEIGHT);
        long p50 = Math.round((measuredP50 * (double) decided + prior.p50DecisionMs() * PRIOR_WEIGHT) / (decided + PRIOR_WEIGHT));
        return new Stats(rate, p50);
    }
}
