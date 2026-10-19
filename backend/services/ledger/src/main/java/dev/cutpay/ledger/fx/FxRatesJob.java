package dev.cutpay.ledger.fx;

import dev.cutpay.events.JobLock;
import java.security.SecureRandom;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Moves the exchange rates a little every minute, so a quote today is not the quote of yesterday. */
@Component
class FxRatesJob {

    private static final Logger log = LoggerFactory.getLogger(FxRatesJob.class);

    private final FxService fx;
    private final JobLock lock;
    private final SecureRandom random = new SecureRandom();

    FxRatesJob(FxService fx, JobLock lock) {
        this.fx = fx;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${cutpay.ledger.fx-rate-interval:PT1M}", initialDelayString = "${cutpay.ledger.fx-rate-interval:PT1M}")
    void run() {
        lock.runLocked("ledger-fx-rates", Duration.ofSeconds(30), () -> {
            fx.stepRates(random);
            log.debug("Exchange rates updated");
        });
    }
}
