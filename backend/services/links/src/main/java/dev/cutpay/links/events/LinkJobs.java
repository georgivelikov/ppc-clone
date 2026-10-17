package dev.cutpay.links.events;

import dev.cutpay.events.JobLock;
import dev.cutpay.links.link.LinkService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background housekeeping, one instance at a time (JobLock). */
@Component
class LinkJobs {

    private static final Logger log = LoggerFactory.getLogger(LinkJobs.class);

    private final LinkService links;
    private final JobLock lock;

    LinkJobs(LinkService links, JobLock lock) {
        this.links = links;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${cutpay.links.job-poll-interval:PT10S}")
    void housekeeping() {
        lock.runLocked("links-housekeeping", Duration.ofMinutes(2), () -> {
            int released = links.releaseExpiredReservations();
            int expired = links.deactivateExpiredLinks();
            if (released > 0 || expired > 0) {
                log.info("Gave back {} timed-out reservations, closed {} expired links", released, expired);
            }
        });
    }
}
