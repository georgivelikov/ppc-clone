package dev.cutpay.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock that runs at normal speed but can be pushed forward, so time-based flows (settlement delays,
 * payout schedules) can be tested in seconds. Only services that set {@code cutpay.clock.simulated=true}
 * get one; every other service keeps the system clock.
 */
public class SimulatedClock extends Clock {

    private final Clock base;
    private final ZoneId zone;
    private final AtomicLong offsetMillis;

    public SimulatedClock(Clock base) {
        this(base, new AtomicLong());
    }

    private SimulatedClock(Clock base, AtomicLong offsetMillis) {
        this.base = base;
        this.zone = base.getZone();
        this.offsetMillis = offsetMillis;
    }

    public void advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("The clock can only move forward");
        }
        offsetMillis.addAndGet(amount.toMillis());
    }

    public Duration offset() {
        return Duration.ofMillis(offsetMillis.get());
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        // Shares the offset, so a zone-converted view stays in step with this clock.
        return new SimulatedClock(base.withZone(newZone), offsetMillis);
    }

    @Override
    public Instant instant() {
        return base.instant().plusMillis(offsetMillis.get());
    }
}
