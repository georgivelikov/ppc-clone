package dev.cutpay.payments.payment;

import dev.cutpay.events.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background housekeeping, one instance at a time (JobLock). */
@Component
class PaymentJobs {

    private static final Logger log = LoggerFactory.getLogger(PaymentJobs.class);

    private final PaymentService payments;
    private final JobLock lock;
    private final Duration stuckAfter;

    PaymentJobs(PaymentService payments, JobLock lock,
                @org.springframework.beans.factory.annotation.Value("${cutpay.payments.stuck-after:PT2M}") Duration stuckAfter) {
        this.payments = payments;
        this.lock = lock;
        this.stuckAfter = stuckAfter;
    }

    /** Payments whose answer from the card network was never recorded (a crash or a lost database between the two steps). */
    @Scheduled(fixedDelayString = "${cutpay.payments.job-poll-interval:PT10S}")
    void resolveStuckPayments() {
        lock.runLocked("payments-resolve-stuck", Duration.ofMinutes(2), () -> {
            int resolved = payments.resolveStuck(stuckAfter, 100);
            if (resolved > 0) {
                log.warn("Settled {} payments that were stuck in processing", resolved);
            }
        });
    }

    @Scheduled(fixedDelayString = "${cutpay.payments.job-poll-interval:PT10S}")
    void releaseExpiredAuthorizations() {
        lock.runLocked("payments-expire-authorizations", Duration.ofMinutes(2), () -> {
            int released = payments.expireUncaptured(100);
            if (released > 0) {
                log.info("Released {} authorisations that were not captured in time", released);
            }
        });
    }
}
