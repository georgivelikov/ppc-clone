package dev.cutpay.notifications.log;

import dev.cutpay.events.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends what failed earlier once its delay is over. */
@Component
class NotificationJobs {

    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);

    private final NotificationService notifications;
    private final JobLock lock;

    NotificationJobs(NotificationService notifications, JobLock lock) {
        this.notifications = notifications;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${cutpay.notifications.retry-poll-interval:PT10S}")
    void retry() {
        lock.runLocked("notifications-retry", Duration.ofMinutes(2), () -> {
            int sent = notifications.deliverDue();
            if (sent > 0) {
                log.info("Sent {} emails that were waiting for a retry", sent);
            }
        });
    }
}
