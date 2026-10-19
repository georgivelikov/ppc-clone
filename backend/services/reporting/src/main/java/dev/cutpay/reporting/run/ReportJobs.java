package dev.cutpay.reporting.run;

import dev.cutpay.events.JobLock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Builds the queued reports one at a time. */
@Component
class ReportJobs {

    private final ReportRunService runs;
    private final JobLock lock;

    ReportJobs(ReportRunService runs, JobLock lock) {
        this.runs = runs;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${cutpay.reporting.job-poll-interval:PT2S}")
    void build() {
        lock.runLocked("reporting-build", Duration.ofMinutes(5), () -> {
            // Keep going while there is work, so a burst of requests is not spread over many polls.
            for (int i = 0; i < 20 && runs.buildNext(); i++) {
                // each call builds one report
            }
        });
    }
}
