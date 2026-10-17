package dev.cutpay.ledger.jobs;

import dev.cutpay.events.JobLock;
import dev.cutpay.ledger.posting.LedgerService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves pending funds to available once their waiting period (T+N, measured on the simulated clock) has
 * passed. {@link JobLock} makes sure that with several ledger instances only one runs it at a time.
 */
@Component
class AvailabilityJob {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityJob.class);
    private static final int BATCH = 100;

    private final LedgerService ledger;
    private final JobLock lock;

    AvailabilityJob(LedgerService ledger, JobLock lock) {
        this.ledger = ledger;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${cutpay.ledger.availability-poll-interval:PT5S}")
    void run() {
        lock.runLocked("ledger-availability", Duration.ofMinutes(5), () -> {
            int released = 0;
            int batch;
            do {
                batch = ledger.releaseDueFunds(BATCH);
                released += batch;
            } while (batch == BATCH);
            if (released > 0) {
                log.info("Made funds from {} payments available", released);
            }
        });
    }
}
