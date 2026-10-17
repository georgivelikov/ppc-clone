package dev.cutpay.webhooks.logs;

import dev.cutpay.events.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RequestLogJobs {

    private static final Logger log = LoggerFactory.getLogger(RequestLogJobs.class);

    private final RequestLogService logs;
    private final JobLock lock;

    RequestLogJobs(RequestLogService logs, JobLock lock) {
        this.logs = logs;
        this.lock = lock;
    }

    /** Hourly; with several instances only one does the delete. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void deleteExpired() {
        lock.runLocked("request-log-retention", Duration.ofMinutes(10), () -> {
            int deleted = logs.deleteExpired();
            if (deleted > 0) {
                log.info("Deleted {} request log entries older than {}", deleted, RequestLogService.RETENTION);
            }
        });
    }
}
