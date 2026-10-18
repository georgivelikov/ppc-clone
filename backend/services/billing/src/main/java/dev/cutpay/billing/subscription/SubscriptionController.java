package dev.cutpay.billing.subscription;

import dev.cutpay.billing.invoice.InvoiceViews.LineView;
import dev.cutpay.billing.subscription.SubscriptionRepository.Subscription;
import dev.cutpay.billing.subscription.SubscriptionService.Changes;
import dev.cutpay.billing.subscription.SubscriptionService.Details;
import dev.cutpay.billing.subscription.SubscriptionService.ItemDetail;
import dev.cutpay.billing.subscription.SubscriptionService.ItemRequest;
import dev.cutpay.billing.subscription.SubscriptionService.NewSubscription;
import dev.cutpay.billing.subscription.SubscriptionService.Stats;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SubscriptionController {

    record ItemInput(@NotBlank String price, @Min(1) @Max(100_000) int quantity) {
        ItemRequest toRequest() {
            return new ItemRequest(price, quantity);
        }
    }

    record CreateSubscription(@NotBlank String customer, @NotEmpty @Valid List<ItemInput> items,
                              @Min(0) @Max(730) Integer trialDays, String collectionMethod, String defaultPaymentMethod,
                              Double taxRatePercent, Map<String, String> metadata,
                              @Size(max = 160) String merchantName) {
    }

    record UpdateSubscription(@Valid List<ItemInput> items, Boolean cancelAtPeriodEnd, String defaultPaymentMethod,
                              String prorationBehavior, Boolean billImmediately) {
    }

    record CancelRequest(Boolean atPeriodEnd) {
    }

    record PreviewRequest(@NotEmpty @Valid List<ItemInput> items) {
    }

    record ItemView(String id, String price, String product, String productName, String nickname, long unitAmount,
                    int quantity, long amount) {
        static ItemView of(ItemDetail d) {
            return new ItemView(d.id(), d.price().id(), d.price().productId(), d.productName(), d.price().nickname(),
                    d.price().unitAmount(), d.quantity(), Math.multiplyExact(d.price().unitAmount(), (long) d.quantity()));
        }
    }

    record SubscriptionView(String id, String object, boolean livemode, String customer, String customerEmail,
                            String customerName, String status, String currency, String interval, int intervalCount,
                            String collectionMethod, String defaultPaymentMethod, Instant currentPeriodStart,
                            Instant currentPeriodEnd, Instant trialEnd, boolean cancelAtPeriodEnd,
                            boolean collectionPaused, Instant canceledAt, Instant endedAt, double taxRatePercent,
                            String latestInvoice, Map<String, String> metadata, List<ItemView> items, Instant createdAt) {
        static SubscriptionView of(Details d) {
            Subscription s = d.subscription();
            return new SubscriptionView(s.id(), "subscription", s.livemode(), s.customerId(), s.customerEmail(),
                    s.customerName(), s.status(), s.currency(), s.interval().wire(), s.intervalCount(),
                    s.collectionMethod(), s.defaultPaymentMethod(), s.currentPeriodStart(), s.currentPeriodEnd(),
                    s.trialEnd(), s.cancelAtPeriodEnd(), s.collectionPaused(), s.canceledAt(), s.endedAt(),
                    s.taxRateBps() / 100.0, s.latestInvoiceId(), s.metadata(), d.items().stream().map(ItemView::of).toList(),
                    s.createdAt());
        }
    }

    record SubscriptionList(String object, List<SubscriptionView> data, boolean hasMore) {
    }

    record ProrationLineView(String description, long unitAmount, int quantity, long amount) {
    }

    record ProrationPreviewView(String currency, Instant periodEnd, List<ProrationLineView> lines, long net) {
    }

    record UpcomingView(String object, String currency, Instant periodStart, Instant periodEnd, List<LineView> lines,
                        long subtotal, long tax, long creditApplied, long total) {
    }

    record StatsView(String object, Map<String, Long> mrr, Map<String, Long> byStatus, long endedLast30Days,
                     List<SubscriptionService.MonthChurn> churnByMonth) {
        static StatsView of(Stats s) {
            return new StatsView("subscription_stats", s.mrr(), s.byStatus(), s.endedLast30Days(), s.churnByMonth());
        }
    }

    private final SubscriptionService service;

    SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    @PostMapping("/v1/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    SubscriptionView create(@AuthenticationPrincipal Jwt jwt,
                            @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                            @Valid @RequestBody CreateSubscription request) {
        int taxBps = request.taxRatePercent() == null ? 0 : (int) Math.round(request.taxRatePercent() * 100);
        return SubscriptionView.of(service.create(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode),
                new NewSubscription(request.customer(), request.merchantName(),
                        request.items().stream().map(ItemInput::toRequest).toList(), request.trialDays(),
                        request.collectionMethod(), request.defaultPaymentMethod(), taxBps, request.metadata())));
    }

    @GetMapping("/v1/subscriptions")
    SubscriptionList list(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                          @RequestParam(required = false) String status, @RequestParam(required = false) String customer,
                          @RequestParam(name = "starting_after", required = false) String startingAfter,
                          @RequestParam(defaultValue = "20") int limit) {
        var page = service.list(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode), status, customer,
                startingAfter, limit);
        return new SubscriptionList("list", page.data().stream().map(SubscriptionView::of).toList(), page.hasMore());
    }

    @GetMapping("/v1/subscriptions/{id}")
    SubscriptionView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return SubscriptionView.of(service.get(merchant(jwt), claim(jwt), id));
    }

    @PatchMapping("/v1/subscriptions/{id}")
    SubscriptionView update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                            @Valid @RequestBody UpdateSubscription request) {
        String behavior = request.prorationBehavior();
        if (behavior != null && !List.of("create_prorations", "none").contains(behavior)) {
            throw ApiException.invalidRequest("invalid_proration_behavior",
                    "Proration behavior must be create_prorations or none", "proration_behavior");
        }
        return SubscriptionView.of(service.update(merchant(jwt), claim(jwt), id,
                new Changes(request.items() == null ? null : request.items().stream().map(ItemInput::toRequest).toList(),
                        request.cancelAtPeriodEnd(), request.defaultPaymentMethod(), behavior,
                        Boolean.TRUE.equals(request.billImmediately()))));
    }

    @PostMapping("/v1/subscriptions/{id}/cancel")
    SubscriptionView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                            @RequestBody(required = false) CancelRequest request) {
        boolean atPeriodEnd = request != null && Boolean.TRUE.equals(request.atPeriodEnd());
        return SubscriptionView.of(service.cancel(merchant(jwt), claim(jwt), id, atPeriodEnd));
    }

    @PostMapping("/v1/subscriptions/{id}/pause")
    SubscriptionView pause(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return SubscriptionView.of(service.pause(merchant(jwt), claim(jwt), id));
    }

    @PostMapping("/v1/subscriptions/{id}/resume")
    SubscriptionView resume(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return SubscriptionView.of(service.resume(merchant(jwt), claim(jwt), id));
    }

    @PostMapping("/v1/subscriptions/{id}/proration_preview")
    ProrationPreviewView preview(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                 @Valid @RequestBody PreviewRequest request) {
        var preview = service.previewChange(merchant(jwt), claim(jwt), id,
                request.items().stream().map(ItemInput::toRequest).toList());
        return new ProrationPreviewView(preview.currency(), preview.periodEnd(),
                preview.lines().stream().map(l -> new ProrationLineView(l.description(), l.unitAmount(), l.quantity(), l.amount())).toList(),
                preview.net());
    }

    @GetMapping("/v1/subscriptions/{id}/upcoming_invoice")
    UpcomingView upcoming(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        var upcoming = service.upcoming(merchant(jwt), claim(jwt), id);
        return new UpcomingView("upcoming_invoice", upcoming.currency(), upcoming.periodStart(), upcoming.periodEnd(),
                upcoming.lines().stream().map(LineView::of).toList(), upcoming.subtotal(), upcoming.tax(),
                upcoming.creditApplied(), upcoming.total());
    }

    @GetMapping("/v1/subscription_stats")
    StatsView stats(@AuthenticationPrincipal Jwt jwt,
                    @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        return StatsView.of(service.stats(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode)));
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }

    private static Boolean claim(Jwt jwt) {
        return jwt.getClaimAsBoolean("livemode");
    }

}
