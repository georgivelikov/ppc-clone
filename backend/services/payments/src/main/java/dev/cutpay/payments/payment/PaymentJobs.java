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

    PaymentJobs(PaymentService payments, JobLock lock) {
        this.payments = payments;
        this.lock = lock;
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
