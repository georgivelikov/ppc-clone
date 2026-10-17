package dev.cutpay.payouts.jobs;

import dev.cutpay.events.JobLock;
import dev.cutpay.payouts.payout.PayoutService;
import dev.cutpay.payouts.payout.PayoutService.Created;
import dev.cutpay.payouts.payout.PayoutService.Group;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The two background jobs. Each takes a {@link JobLock} so that with several payouts instances only one
 * does the work at a time. They wake up in real time but decide what is due using the simulated clock.
 */
@Component
class PayoutJobs {

    private static final Logger log = LoggerFactory.getLogger(PayoutJobs.class);
    private static final int BATCH = 100;

    private final PayoutService payouts;
    private final JobLock lock;

    PayoutJobs(PayoutService payouts, JobLock lock) {
        this.payouts = payouts;
        this.lock = lock;
    }

    /** Creates the scheduled payouts that are due today. */
    @Scheduled(fixedDelayString = "${cutpay.payouts.job-poll-interval:PT5S}")
    void createScheduledPayouts() {
        lock.runLocked("payouts-schedule", Duration.ofMinutes(5), () -> {
            for (Group group : payouts.groupsWithPayableFunds()) {
                try {
                    Optional<Created> created = payouts.createScheduledPayout(group);
                    created.ifPresent(c -> log.info("Created payout {} for {} {} ({})", c.payoutId(), c.amount(),
                            c.currency(), group.merchantId()));
                } catch (RuntimeException e) {
                    // One merchant's failure must not stop everyone else's payout.
                    log.error("Could not create the payout for {}", group, e);
                }
            }
        });
    }

    /** The fake bank confirms (or rejects) transfers that have been in transit long enough. */
    @Scheduled(fixedDelayString = "${cutpay.payouts.job-poll-interval:PT5S}")
    void settleTransfers() {
        lock.runLocked("payouts-bank", Duration.ofMinutes(5), () -> {
            int settled;
            do {
                settled = payouts.settleDuePayouts(BATCH);
                if (settled > 0) {
                    log.info("The fake bank settled {} payouts", settled);
                }
            } while (settled == BATCH);
        });
    }
}
