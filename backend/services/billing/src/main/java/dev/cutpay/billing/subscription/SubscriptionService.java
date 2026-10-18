package dev.cutpay.billing.subscription;

import dev.cutpay.billing.catalog.CatalogService;
import dev.cutpay.billing.catalog.CatalogService.Price;
import dev.cutpay.billing.events.BillingEvents;
import dev.cutpay.billing.events.NotificationLog;
import dev.cutpay.billing.invoice.InvoiceRepository;
import dev.cutpay.billing.invoice.InvoiceRepository.Invoice;
import dev.cutpay.billing.invoice.InvoiceRepository.Line;
import dev.cutpay.billing.invoice.InvoiceRepository.PendingItem;
import dev.cutpay.billing.invoice.InvoiceService;
import dev.cutpay.billing.invoice.InvoiceService.Totals;
import dev.cutpay.billing.payments.PaymentsClient;
import dev.cutpay.billing.subscription.Proration.Item;
import dev.cutpay.billing.subscription.SubscriptionRepository.Subscription;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Subscriptions and the billing run. A subscription is billed in advance: its first invoice is created when it starts
 * (or when its trial ends), and every time a period ends the next period is invoiced and charged. Each step that
 * changes a subscription takes its row lock, so two workers can never bill the same period twice.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    public record ItemRequest(String price, int quantity) {
    }

    public record NewSubscription(String customerId, String merchantName, List<ItemRequest> items, Integer trialDays,
                                  String collectionMethod, String defaultPaymentMethod, Integer taxRateBps,
                                  Map<String, String> metadata) {
    }

    public record Changes(List<ItemRequest> items, Boolean cancelAtPeriodEnd, String defaultPaymentMethod,
                          String prorationBehavior, boolean billImmediately) {
    }

    /** An item of a subscription with what it costs and what it is called. */
    public record ItemDetail(String id, Price price, String productName, int quantity) {
    }

    public record Details(Subscription subscription, List<ItemDetail> items) {
    }

    public record ProrationPreview(String currency, Instant periodEnd, List<Proration.Line> lines, long net) {
    }

    public record Upcoming(String currency, Instant periodStart, Instant periodEnd, List<Line> lines, long subtotal,
                           long tax, long total) {
    }

    public record Stats(Map<String, Long> mrr, Map<String, Long> byStatus, long endedLast30Days) {
    }

    private record Resolved(Price price, String productName, int quantity) {
        Item item() {
            return new Item(price.id(), productName, price.unitAmount(), quantity);
        }
    }

    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;
    private final CatalogService catalog;
    private final PaymentsClient payments;
    private final BillingEvents events;
    private final NotificationLog notifications;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository subscriptions, InvoiceRepository invoices,
                               InvoiceService invoiceService, CatalogService catalog, PaymentsClient payments,
                               BillingEvents events, NotificationLog notifications, TransactionTemplate tx, Clock clock) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
        this.catalog = catalog;
        this.payments = payments;
        this.events = events;
        this.notifications = notifications;
        this.tx = tx;
        this.clock = clock;
    }

    // --- starting a subscription -------------------------------------------------------------------

    public Details create(String merchantId, boolean livemode, NewSubscription request) {
        var customer = payments.customer(merchantId, request.customerId());
        if (customer.livemode() != livemode) {
            throw ApiException.notFound("customer_not_found", "No such customer");
        }
        List<Resolved> items = resolve(merchantId, livemode, request.items());
        Price first = items.get(0).price();
        String method = request.collectionMethod() == null ? "charge_automatically" : request.collectionMethod();
        if (!"charge_automatically".equals(method) && !"send_invoice".equals(method)) {
            throw ApiException.invalidRequest("invalid_collection_method",
                    "Collection method must be charge_automatically or send_invoice", "collection_method");
        }
        if ("charge_automatically".equals(method) && request.defaultPaymentMethod() == null
                && customer.defaultPaymentMethod() == null) {
            throw ApiException.invalidRequest("no_payment_method",
                    "The customer has no saved card. Save one first, or use send_invoice", "customer");
        }
        int trialDays = request.trialDays() != null ? request.trialDays()
                : items.stream().mapToInt(i -> i.price().trialDays()).max().orElse(0);
        if (trialDays < 0 || trialDays > 730) {
            throw ApiException.invalidRequest("invalid_trial", "Trial days must be between 0 and 730", "trial_days");
        }
        int taxBps = request.taxRateBps() == null ? 0 : request.taxRateBps();
        if (taxBps < 0 || taxBps > 10_000) {
            throw ApiException.invalidRequest("invalid_tax_rate", "Tax rate must be between 0 and 100", "tax_rate_percent");
        }

        Instant now = clock.instant();
        boolean trial = trialDays > 0;
        Instant anchor = trial ? now.plus(Duration.ofDays(trialDays)) : now;
        int index = trial ? -1 : 0;
        Instant start = trial ? now : anchor;
        Instant end = Periods.boundary(anchor, first.interval(), first.intervalCount(), index + 1);
        String id = Ids.generate("sub");
        var sub = new Subscription(id, merchantId, request.merchantName(), livemode, customer.id(), customer.email(),
                customer.name(), trial ? SubscriptionRepository.TRIALING : SubscriptionRepository.INCOMPLETE,
                first.currency(), first.interval(), first.intervalCount(), method, request.defaultPaymentMethod(), anchor,
                index, start, end, trial ? anchor : null, false, false, null, null, taxBps, null,
                request.metadata() == null ? Map.of() : request.metadata(), now, now,
                items.stream().map(i -> new SubscriptionRepository.Item(Ids.generate("si"), i.price().id(), i.quantity())).toList());

        String invoiceId = tx.execute(status -> {
            subscriptions.insert(sub);
            events.subscription("SubscriptionCreated", sub);
            if (trial) {
                return null;
            }
            Invoice invoice = invoiceService.createSubscriptionInvoice(sub, "subscription_create",
                    lines(items, start, end), start, end, false);
            subscriptions.setLatestInvoice(id, invoice.id(), now);
            if ("send_invoice".equals(method)) {
                invoiceService.announce(invoice);
            }
            return invoice.open() && "charge_automatically".equals(method) ? invoice.id() : null;
        });
        if (invoiceId != null) {
            invoiceService.collect(invoiceId, true);
        }
        return details(subscriptions.find(id).orElseThrow());
    }

    // --- reading -----------------------------------------------------------------------------------

    public Details get(String merchantId, Boolean claimedMode, String id) {
        return details(owned(merchantId, claimedMode, id));
    }

    public record Page(List<Details> data, boolean hasMore) {
    }

    public Page list(String merchantId, boolean livemode, String status, String customerId, String startingAfter, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        List<Subscription> found = subscriptions.page(merchantId, livemode, blank(status), blank(customerId),
                blank(startingAfter), size + 1);
        return new Page(found.stream().limit(size).map(this::details).toList(), found.size() > size);
    }

    public Stats stats(String merchantId, boolean livemode) {
        Map<String, Long> mrr = new java.util.TreeMap<>();
        for (var row : subscriptions.recurringRevenue(merchantId, livemode)) {
            long monthly = Periods.monthlyAmount(Math.multiplyExact(row.unitAmount(), (long) row.quantity()),
                    row.interval(), row.intervalCount());
            mrr.merge(row.currency(), monthly, Long::sum);
        }
        Map<String, Long> byStatus = new java.util.TreeMap<>();
        subscriptions.countByStatus(merchantId, livemode).forEach(c -> byStatus.put(c.status(), c.count()));
        long ended = subscriptions.endedSince(merchantId, livemode, clock.instant().minus(Duration.ofDays(30)));
        return new Stats(mrr, byStatus, ended);
    }

    // --- changing a subscription -------------------------------------------------------------------

    /** What changing to {@code items} would cost for the rest of the period, without changing anything. */
    public ProrationPreview previewChange(String merchantId, Boolean claimedMode, String id, List<ItemRequest> items) {
        Subscription sub = owned(merchantId, claimedMode, id);
        List<Resolved> next = resolve(merchantId, sub.livemode(), items);
        requireSameTerms(sub, next);
        List<Proration.Line> lines = isTrial(sub) ? List.of()
                : Proration.change(currentItems(sub), next.stream().map(Resolved::item).toList(), clock.instant(),
                sub.currentPeriodStart(), sub.currentPeriodEnd());
        return new ProrationPreview(sub.currency(), sub.currentPeriodEnd(), lines, Proration.net(lines));
    }

    /** The invoice the next renewal will produce, with any waiting proration lines. */
    public Upcoming upcoming(String merchantId, Boolean claimedMode, String id) {
        Subscription sub = owned(merchantId, claimedMode, id);
        if (sub.ended()) {
            throw ApiException.conflict("subscription_ended", "This subscription has ended", null);
        }
        int next = sub.periodIndex() + 1;
        Instant start = Periods.boundary(sub.billingCycleAnchor(), sub.interval(), sub.intervalCount(), next);
        Instant end = Periods.boundary(sub.billingCycleAnchor(), sub.interval(), sub.intervalCount(), next + 1);
        List<Line> lines = new ArrayList<>(lines(resolveCurrent(sub), start, end));
        for (PendingItem item : invoices.pending(sub.id())) {
            lines.add(new Line(item.id(), item.description(), item.unitAmount(), item.quantity(), item.amount(),
                    item.priceId(), item.proration(), item.periodStart(), item.periodEnd()));
        }
        Totals totals = Totals.of(lines, sub.taxRateBps());
        return new Upcoming(sub.currency(), start, end, lines, totals.subtotal(), totals.tax(), Math.max(0, totals.total()));
    }

    public Details update(String merchantId, Boolean claimedMode, String id, Changes changes) {
        String invoiceToCollect = tx.execute(status -> {
            Subscription sub = lockOwned(merchantId, claimedMode, id);
            if (sub.ended()) {
                throw ApiException.conflict("subscription_ended", "This subscription has ended", null);
            }
            Instant now = clock.instant();
            String collect = null;
            if (changes.items() != null) {
                List<Resolved> next = resolve(merchantId, sub.livemode(), changes.items());
                requireSameTerms(sub, next);
                boolean prorate = !"none".equals(changes.prorationBehavior()) && !isTrial(sub);
                if (prorate) {
                    var lines = Proration.change(currentItems(sub), next.stream().map(Resolved::item).toList(), now,
                            sub.currentPeriodStart(), sub.currentPeriodEnd());
                    for (Proration.Line line : lines) {
                        invoices.addPending(sub.merchantId(), sub.livemode(), sub.id(),
                                new PendingItem(Ids.generate("pii"), line.description(), line.unitAmount(), line.quantity(),
                                        line.amount(), line.priceId(), true, now, sub.currentPeriodEnd()), now);
                    }
                }
                subscriptions.replaceItems(id, next.stream()
                        .map(i -> new SubscriptionRepository.Item(Ids.generate("si"), i.price().id(), i.quantity())).toList());
                if (changes.billImmediately() && prorate) {
                    collect = billPendingNow(sub, now);
                }
            }
            if (changes.cancelAtPeriodEnd() != null) {
                subscriptions.setFlags(id, changes.cancelAtPeriodEnd(), sub.collectionPaused(), now);
            }
            if (changes.defaultPaymentMethod() != null) {
                subscriptions.setDefaultPaymentMethod(id, changes.defaultPaymentMethod(), now);
            }
            events.subscription("SubscriptionUpdated", subscriptions.find(id).orElseThrow());
            return collect;
        });
        if (invoiceToCollect != null) {
            invoiceService.collect(invoiceToCollect, true);
        }
        return details(subscriptions.find(id).orElseThrow());
    }

    /** Invoices the waiting proration lines right away, if they come to something to pay. */
    private String billPendingNow(Subscription sub, Instant now) {
        List<PendingItem> waiting = invoices.pending(sub.id());
        long net = waiting.stream().mapToLong(PendingItem::amount).sum();
        if (net <= 0) {
            return null; // a credit stays for the next renewal
        }
        Invoice invoice = invoiceService.createSubscriptionInvoice(sub, "subscription_update", List.of(),
                sub.currentPeriodStart(), sub.currentPeriodEnd(), true);
        subscriptions.setLatestInvoice(sub.id(), invoice.id(), now);
        return invoice.open() && "charge_automatically".equals(sub.collectionMethod()) ? invoice.id() : null;
    }

    public Details cancel(String merchantId, Boolean claimedMode, String id, boolean atPeriodEnd) {
        tx.executeWithoutResult(status -> {
            Subscription sub = lockOwned(merchantId, claimedMode, id);
            if (sub.ended()) {
                throw ApiException.conflict("subscription_ended", "This subscription has already ended", null);
            }
            Instant now = clock.instant();
            if (atPeriodEnd) {
                subscriptions.setFlags(id, true, sub.collectionPaused(), now);
                events.subscription("SubscriptionUpdated", subscriptions.find(id).orElseThrow());
            } else {
                endSubscription(sub, now, "canceled");
            }
        });
        return details(subscriptions.find(id).orElseThrow());
    }

    /** Stops charging without ending the subscription. Resume to start again. */
    public Details pause(String merchantId, Boolean claimedMode, String id) {
        tx.executeWithoutResult(status -> {
            Subscription sub = lockOwned(merchantId, claimedMode, id);
            if (sub.ended() || sub.collectionPaused()) {
                throw ApiException.conflict("not_pausable", "This subscription is already paused or has ended", null);
            }
            subscriptions.setFlags(id, sub.cancelAtPeriodEnd(), true, clock.instant());
            events.subscription("SubscriptionUpdated", subscriptions.find(id).orElseThrow());
        });
        return details(subscriptions.find(id).orElseThrow());
    }

    /**
     * Starts charging again. If the paid period is still running nothing is billed yet. If it ran out during the pause,
     * a new cycle starts now and its first period is billed straight away.
     */
    public Details resume(String merchantId, Boolean claimedMode, String id) {
        String invoiceToCollect = tx.execute(status -> {
            Subscription sub = lockOwned(merchantId, claimedMode, id);
            if (!sub.collectionPaused()) {
                throw ApiException.conflict("not_paused", "This subscription is not paused", null);
            }
            Instant now = clock.instant();
            if (sub.currentPeriodEnd().isAfter(now)) {
                subscriptions.setFlags(id, sub.cancelAtPeriodEnd(), false, now);
                events.subscription("SubscriptionUpdated", subscriptions.find(id).orElseThrow());
                return null;
            }
            Instant end = Periods.boundary(now, sub.interval(), sub.intervalCount(), 1);
            subscriptions.restartCycle(id, now, end, now);
            Subscription restarted = subscriptions.find(id).orElseThrow();
            Invoice invoice = invoiceService.createSubscriptionInvoice(restarted, "subscription_cycle",
                    lines(resolveCurrent(restarted), now, end), now, end, true);
            subscriptions.setLatestInvoice(id, invoice.id(), now);
            events.subscription("SubscriptionUpdated", subscriptions.find(id).orElseThrow());
            return invoice.open() && "charge_automatically".equals(sub.collectionMethod()) ? invoice.id() : null;
        });
        if (invoiceToCollect != null) {
            invoiceService.collect(invoiceToCollect, true);
        }
        return details(subscriptions.find(id).orElseThrow());
    }

    // --- the billing run ---------------------------------------------------------------------------

    /**
     * Bills the subscriptions whose period has ended, ends those set to cancel, and gives up on first invoices that
     * were never paid. Returns the number of subscriptions handled.
     */
    public int runDueBilling(int limit) {
        int handled = 0;
        for (String id : subscriptions.dueForBilling(clock.instant(), limit)) {
            try {
                String invoiceId = tx.execute(status -> billOne(id));
                if (invoiceId != null) {
                    invoiceService.collect(invoiceId, true);
                }
                handled++;
            } catch (RuntimeException e) {
                log.warn("Billing subscription {} failed", id, e);
            }
        }
        Instant cutoff = clock.instant().minus(Duration.ofHours(24));
        for (String id : subscriptions.incompleteSince(cutoff, limit)) {
            try {
                tx.executeWithoutResult(status -> expireIncomplete(id));
                handled++;
            } catch (RuntimeException e) {
                log.warn("Expiring subscription {} failed", id, e);
            }
        }
        return handled;
    }

    /** Creates the invoice for the next period of one subscription. Returns the invoice to charge, if any. */
    private String billOne(String id) {
        Subscription sub = subscriptions.lock(id).orElse(null);
        Instant now = clock.instant();
        boolean due = sub != null && !sub.collectionPaused() && !sub.currentPeriodEnd().isAfter(now)
                && (SubscriptionRepository.ACTIVE.equals(sub.status()) || SubscriptionRepository.TRIALING.equals(sub.status()));
        if (!due) {
            return null;
        }
        if (sub.cancelAtPeriodEnd()) {
            endSubscription(sub, now, "canceled_at_period_end");
            return null;
        }
        int next = sub.periodIndex() + 1;
        Instant start = Periods.boundary(sub.billingCycleAnchor(), sub.interval(), sub.intervalCount(), next);
        Instant end = Periods.boundary(sub.billingCycleAnchor(), sub.interval(), sub.intervalCount(), next + 1);
        Invoice invoice = invoiceService.createSubscriptionInvoice(sub, "subscription_cycle",
                lines(resolveCurrent(sub), start, end), start, end, true);
        subscriptions.advancePeriod(id, next, start, end, invoice.id(), now);
        if ("send_invoice".equals(sub.collectionMethod())) {
            invoiceService.announce(invoice);
        }
        return invoice.open() && "charge_automatically".equals(sub.collectionMethod()) ? invoice.id() : null;
    }

    /** A first invoice nobody paid within the allowed time: the subscription never started. */
    private void expireIncomplete(String id) {
        Subscription sub = subscriptions.lock(id).orElse(null);
        if (sub == null || !SubscriptionRepository.INCOMPLETE.equals(sub.status())) {
            return;
        }
        Instant now = clock.instant();
        if (sub.latestInvoiceId() != null) {
            invoices.lock(sub.latestInvoiceId()).filter(Invoice::open).ifPresent(invoice -> {
                invoices.setStatus(invoice.id(), InvoiceRepository.VOID, now);
                invoices.cancelPendingAttempts(invoice.id(), now);
                events.invoice("InvoiceVoided", invoices.find(invoice.id()).orElseThrow());
            });
        }
        endSubscription(sub, now, "incomplete_expired");
    }

    private void endSubscription(Subscription sub, Instant now, String reason) {
        subscriptions.end(sub.id(), now);
        events.subscription("SubscriptionCanceled", subscriptions.find(sub.id()).orElseThrow());
        notifications.send(sub.merchantId(), sub.livemode(), "subscription_canceled", sub.customerEmail(),
                "Your subscription was canceled",
                "Your subscription %s has ended (%s). You will not be charged again.".formatted(sub.id(), reason.replace('_', ' ')),
                sub.id());
    }

    // --- helpers -----------------------------------------------------------------------------------

    private Details details(Subscription sub) {
        List<ItemDetail> items = sub.items().stream().map(item -> {
            Price price = catalog.price(sub.merchantId(), null, item.priceId());
            return new ItemDetail(item.id(), price, catalog.product(sub.merchantId(), null, price.productId()).name(),
                    item.quantity());
        }).toList();
        return new Details(sub, items);
    }

    private List<Resolved> resolve(String merchantId, boolean livemode, List<ItemRequest> requested) {
        if (requested == null || requested.isEmpty()) {
            throw ApiException.invalidRequest("items_required", "A subscription needs at least one item", "items");
        }
        List<Resolved> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ItemRequest item : requested) {
            if (item.quantity() < 1 || item.quantity() > 100_000) {
                throw ApiException.invalidRequest("invalid_quantity", "Quantity must be between 1 and 100000", "items");
            }
            if (!seen.add(item.price())) {
                throw ApiException.invalidRequest("duplicate_item", "Each price can appear once", "items");
            }
            Price price = catalog.price(merchantId, livemode, item.price());
            if (!price.active()) {
                throw ApiException.invalidRequest("price_archived", "This price can no longer be bought", "items");
            }
            if (!price.recurring()) {
                throw ApiException.invalidRequest("price_not_recurring", "A subscription needs recurring prices", "items");
            }
            resolved.add(new Resolved(price, catalog.product(merchantId, livemode, price.productId()).name()
                    + (price.nickname() == null ? "" : " (" + price.nickname() + ")"), item.quantity()));
        }
        Price first = resolved.get(0).price();
        for (Resolved r : resolved) {
            Price p = r.price();
            if (!p.currency().equals(first.currency()) || p.interval() != first.interval()
                    || p.intervalCount() != first.intervalCount()) {
                throw ApiException.invalidRequest("mixed_terms",
                        "All items of a subscription need the same currency and billing interval", "items");
            }
        }
        return resolved;
    }

    private static void requireSameTerms(Subscription sub, List<Resolved> next) {
        Price p = next.get(0).price();
        if (!p.currency().equals(sub.currency()) || p.interval() != sub.interval() || p.intervalCount() != sub.intervalCount()) {
            throw ApiException.invalidRequest("terms_changed",
                    "The new prices must use the same currency and billing interval as the subscription", "items");
        }
    }

    private List<Resolved> resolveCurrent(Subscription sub) {
        return sub.items().stream().map(item -> {
            Price price = catalog.price(sub.merchantId(), null, item.priceId());
            return new Resolved(price, catalog.product(sub.merchantId(), null, price.productId()).name()
                    + (price.nickname() == null ? "" : " (" + price.nickname() + ")"), item.quantity());
        }).toList();
    }

    private List<Item> currentItems(Subscription sub) {
        return resolveCurrent(sub).stream().map(Resolved::item).toList();
    }

    private static List<Line> lines(List<Resolved> items, Instant start, Instant end) {
        return items.stream().map(i -> new Line(Ids.generate("il"), i.productName(), i.price().unitAmount(), i.quantity(),
                Math.multiplyExact(i.price().unitAmount(), (long) i.quantity()), i.price().id(), false, start, end)).toList();
    }

    private static boolean isTrial(Subscription sub) {
        return SubscriptionRepository.TRIALING.equals(sub.status());
    }

    private Subscription owned(String merchantId, Boolean claimedMode, String id) {
        return subscriptions.find(id).filter(s -> s.merchantId().equals(merchantId))
                .filter(s -> claimedMode == null || claimedMode == s.livemode())
                .orElseThrow(() -> ApiException.notFound("subscription_not_found", "No such subscription"));
    }

    private Subscription lockOwned(String merchantId, Boolean claimedMode, String id) {
        return subscriptions.lock(id).filter(s -> s.merchantId().equals(merchantId))
                .filter(s -> claimedMode == null || claimedMode == s.livemode())
                .orElseThrow(() -> ApiException.notFound("subscription_not_found", "No such subscription"));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
