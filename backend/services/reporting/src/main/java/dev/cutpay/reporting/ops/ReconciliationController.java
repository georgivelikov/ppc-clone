package dev.cutpay.reporting.ops;

import dev.cutpay.events.JobLock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReconciliationController {

    private final Reconciliation reconciliation;

    ReconciliationController(Reconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping("/v1/staff/reconciliation/runs")
    Map<String, Object> runs(@AuthenticationPrincipal Jwt jwt) {
        StaffGuard.staff(jwt);
        return Map.of("object", "list", "data", reconciliation.latest(30));
    }

    @GetMapping("/v1/staff/reconciliation/runs/{id}/mismatches")
    Map<String, Object> mismatches(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        StaffGuard.staff(jwt);
        return Map.of("object", "list", "data", reconciliation.mismatches(id));
    }

    /** Runs now and answers when it has finished. {@code date} is a UTC day; without it the whole acquirer file is compared. */
    @PostMapping("/v1/staff/reconciliation/runs")
    Reconciliation.Run start(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) LocalDate date) {
        StaffGuard.staff(jwt);
        return reconciliation.run(date);
    }
}

/** Every night at 02:00 UTC: yesterday's money, as the acquirer reports it. */
@Component
class ReconciliationJobs {

    private final Reconciliation reconciliation;
    private final JobLock lock;

    ReconciliationJobs(Reconciliation reconciliation, JobLock lock) {
        this.reconciliation = reconciliation;
        this.lock = lock;
    }

    @Scheduled(cron = "${cutpay.reconciliation.cron:0 0 2 * * *}", zone = "UTC")
    void nightly() {
        lock.runLocked("reconciliation-nightly", Duration.ofMinutes(30),
                () -> reconciliation.run(LocalDate.now(ZoneOffset.UTC).minusDays(1)));
    }
}
