package dev.cutpay.webhooks.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * When a failed delivery is tried again: 1 minute, 5 minutes, 30 minutes, 2 hours, 5 hours, then every
 * 10 hours, until the event is older than the give-up window (3 days by default).
 */
public final class RetrySchedule {

    private static final List<Duration> DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(5), Duration.ofHours(10));

    private RetrySchedule() {
    }

    /** Wait before the next try after {@code failedAttempts} failures (1 = the first try just failed). */
    public static Duration delayAfter(int failedAttempts) {
        int index = Math.min(Math.max(failedAttempts, 1), DELAYS.size()) - 1;
        return DELAYS.get(index);
    }

    /** The next attempt time, or empty when it would fall outside the give-up window. */
    public static Optional<Instant> nextAttempt(int failedAttempts, Instant now, Instant eventCreatedAt,
                                                Duration giveUpAfter) {
        Instant next = now.plus(delayAfter(failedAttempts));
        return next.isAfter(eventCreatedAt.plus(giveUpAfter)) ? Optional.empty() : Optional.of(next);
    }
}
