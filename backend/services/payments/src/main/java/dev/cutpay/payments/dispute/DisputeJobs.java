package dev.cutpay.payments.dispute;

import dev.cutpay.events.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Moves disputes along: opens the ones that are due, loses the ones nobody answered, decides the ones under review. */
@Component
class DisputeJobs {

    private static final Logger log = LoggerFactory.getLogger(DisputeJobs.class);

    private final DisputeService disputes;
    private final JobLock lock;

    DisputeJobs(DisputeService disputes, JobLock lock) {
        this.disputes = disputes;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${cutpay.payments.dispute-poll-interval:PT3S}")
    void advance() {
        lock.runLocked("payments-disputes", Duration.ofMinutes(2), () -> {
            int opened = disputes.openDue(50);
            int overdue = disputes.closeOverdue(50);
            int decided = disputes.decideReviews(50);
            if (opened + overdue + decided > 0) {
                log.info("Disputes: {} opened, {} lost by default, {} decided", opened, overdue, decided);
            }
        });
    }
}
