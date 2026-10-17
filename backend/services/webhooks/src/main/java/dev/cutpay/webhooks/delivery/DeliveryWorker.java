package dev.cutpay.webhooks.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wakes up in real time and sends whatever is due on the (possibly simulated) clock. Needs no {@code JobLock}:
 * claiming uses {@code SKIP LOCKED}, so any number of instances can poll at once.
 */
@Component
class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryService deliveries;

    DeliveryWorker(DeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @Scheduled(fixedDelayString = "${cutpay.webhooks.poll-interval:PT1S}")
    void poll() {
        try {
            int processed;
            do {
                processed = deliveries.processDue();
                if (processed > 0) {
                    log.debug("Processed {} webhook deliveries", processed);
                }
            } while (processed > 0);
        } catch (RuntimeException e) {
            log.error("Webhook delivery poll failed", e);
        }
    }
}
