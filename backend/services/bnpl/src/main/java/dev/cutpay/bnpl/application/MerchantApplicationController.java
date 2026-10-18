package dev.cutpay.bnpl.application;

import dev.cutpay.bnpl.application.ApplicationRepository.Application;
import dev.cutpay.bnpl.model.ProviderCatalog;
import dev.cutpay.web.RequestMode;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The merchant looking at the applications of their customers. */
@RestController
class MerchantApplicationController {

    record ApplicationView(String id, String object, boolean livemode, String payment, String provider, String providerName,
                           String plan, String status, String providerRef, long amount, String currency, String country,
                           int instalments, List<Long> instalmentAmounts, int intervalDays, double apr, long totalPayable,
                           long merchantFee, long netAmount, String customerEmail, String declineReason, Long decisionMs,
                           Instant expiresAt, Instant createdAt, Instant decidedAt) {
        static ApplicationView of(Application a) {
            return new ApplicationView(a.id(), "bnpl_application", a.livemode(), a.paymentId(), a.provider(),
                    ProviderCatalog.find(a.provider()).map(p -> p.name()).orElse(a.provider()), a.plan(), a.status().wire(),
                    a.providerRef(), a.amount(), a.currency(), a.country(), a.instalmentCount(), a.instalments(),
                    a.intervalDays(), a.aprPercent(), a.totalPayable(), a.merchantFee(), a.amount() - a.merchantFee(),
                    a.customerEmail(), a.declineReason(), a.decisionMs(), a.expiresAt(), a.createdAt(), a.decidedAt());
        }
    }

    record ApplicationList(String object, List<ApplicationView> data, boolean hasMore) {
    }

    record AnalyticsView(String object, int days, List<ApplicationService.ProviderAnalytics> providers, long applications,
                         long approved, long volume, long fees) {
    }

    private final ApplicationService service;

    MerchantApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping("/v1/bnpl/applications")
    ApplicationList list(@AuthenticationPrincipal Jwt jwt,
                         @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                         @RequestParam(required = false) String status, @RequestParam(required = false) String provider,
                         @RequestParam(required = false) String payment,
                         @RequestParam(name = "starting_after", required = false) String startingAfter,
                         @RequestParam(defaultValue = "20") int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        List<Application> found = service.list(jwt.getClaimAsString("mid"),
                RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode), status, provider, payment,
                startingAfter, size);
        return new ApplicationList("list", found.stream().limit(size).map(ApplicationView::of).toList(), found.size() > size);
    }

    /** How each provider has done for the merchant over the last {@code days} days, and the state of its circuit now. */
    @GetMapping("/v1/bnpl/analytics")
    AnalyticsView analytics(@AuthenticationPrincipal Jwt jwt,
                            @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                            @RequestParam(defaultValue = "30") int days) {
        int period = Math.max(1, Math.min(days, 365));
        var rows = service.analytics(jwt.getClaimAsString("mid"),
                RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode), period);
        return new AnalyticsView("bnpl_analytics", period, rows, rows.stream().mapToLong(r -> r.applications()).sum(),
                rows.stream().mapToLong(r -> r.approved()).sum(), rows.stream().mapToLong(r -> r.volume()).sum(),
                rows.stream().mapToLong(r -> r.fees()).sum());
    }

    @GetMapping("/v1/bnpl/applications/{id}")
    ApplicationView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                        @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        return ApplicationView.of(service.get(jwt.getClaimAsString("mid"),
                RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode), id));
    }
}
