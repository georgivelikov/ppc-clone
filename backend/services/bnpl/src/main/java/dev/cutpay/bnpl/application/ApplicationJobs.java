package dev.cutpay.bnpl.application;

import dev.cutpay.events.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks providers about applications that wait for a decision, and closes those nobody decided in time. One instance at a
 * time (JobLock); expiry runs on the simulated clock, so a test can move a day forward and watch an application expire.
 */
@Component
class ApplicationJobs {

    private static final Logger log = LoggerFactory.getLogger(ApplicationJobs.class);

    private final ApplicationService service;
    private final JobLock lock;

    ApplicationJobs(ApplicationService service, JobLock lock) {
        this.service = service;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${cutpay.bnpl.job-poll-interval:PT5S}")
    void run() {
        lock.runLocked("bnpl-applications", Duration.ofMinutes(5), () -> {
            int answered = service.pollWaiting(100);
            int expired = service.expireStale(100);
            if (answered > 0 || expired > 0) {
                log.info("Applications: {} decided by asking the provider, {} expired", answered, expired);
            }
        });
    }
}
