package dev.cutpay.billing.events;

import dev.cutpay.billing.invoice.InvoiceService;
import dev.cutpay.billing.subscription.SubscriptionService;
import dev.cutpay.events.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The billing run: renew subscriptions whose period ended, then retry unpaid invoices that are due. One instance at a
 * time (JobLock), on the simulated clock, so a test can move time forward a month and watch the renewal happen.
 */
@Component
class BillingJobs {

    private static final Logger log = LoggerFactory.getLogger(BillingJobs.class);

    private final SubscriptionService subscriptions;
    private final InvoiceService invoices;
    private final JobLock lock;

    BillingJobs(SubscriptionService subscriptions, InvoiceService invoices, JobLock lock) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${cutpay.billing.job-poll-interval:PT5S}")
    void run() {
        lock.runLocked("billing-run", Duration.ofMinutes(5), () -> {
            int billed = subscriptions.runDueBilling(100);
            int retried = invoices.runDueRetries(100);
            if (billed > 0 || retried > 0) {
                log.info("Billing run: {} subscriptions handled, {} retries", billed, retried);
            }
        });
    }
}
