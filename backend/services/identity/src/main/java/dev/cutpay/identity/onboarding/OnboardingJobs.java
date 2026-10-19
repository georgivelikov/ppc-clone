package dev.cutpay.identity.onboarding;

import dev.cutpay.events.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Asks the verification provider for answers and decides the applications that need no person. */
@Component
class OnboardingJobs {

    private static final Logger log = LoggerFactory.getLogger(OnboardingJobs.class);

    private final OnboardingService onboarding;
    private final JobLock lock;

    OnboardingJobs(OnboardingService onboarding, JobLock lock) {
        this.onboarding = onboarding;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${cutpay.kyc.poll-interval:PT3S}")
    void poll() {
        lock.runLocked("identity-onboarding", Duration.ofMinutes(2), () -> {
            int decided = onboarding.pollVerifications();
            if (decided > 0) {
                log.info("Decided {} onboarding applications", decided);
            }
        });
    }
}
