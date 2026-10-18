package dev.cutpay.billing.invoice;

import dev.cutpay.billing.config.BillingProperties;
import dev.cutpay.billing.events.BillingEvents;
import dev.cutpay.billing.events.NotificationLog;
import dev.cutpay.billing.invoice.InvoiceRepository.Invoice;
import dev.cutpay.billing.invoice.InvoiceRepository.Line;
import dev.cutpay.billing.invoice.InvoiceRepository.PendingItem;
import dev.cutpay.billing.payments.PaymentsClient;
import dev.cutpay.billing.payments.PaymentsClient.Charge;
import dev.cutpay.billing.settings.BillingSettings;
import dev.cutpay.billing.settings.BillingSettings.Settings;
import dev.cutpay.billing.subscription.SubscriptionRepository;
import dev.cutpay.billing.subscription.SubscriptionRepository.Subscription;
import dev.cutpay.money.SupportedCurrencies;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invoices and getting them paid. An invoice is created and committed first, then the card is charged outside any
 * database transaction (the call to the card network can be slow), and the answer is recorded in a second one. The
 * result of a charge also arrives as a payment event, which is how an invoice paid on its hosted page is settled, so
 * both paths end in the same idempotent step: an invoice is paid once and a failure is counted once.
 *
 * <p>When a charge for a subscription fails, the invoice is retried on the days the merchant chose (1, 3, 5 and 7 days
 * after the first failure by default). After the last failed retry the subscription is canceled or marked unpaid,
 * whichever the merchant picked, and the customer is emailed at every step.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    public record DraftLine(String description, long unitAmount, int quantity) {
    }

    public record NewInvoice(String customerId, String merchantName, String currency, List<DraftLine> lines,
                             String collectionMethod, Integer daysUntilDue, Integer taxRateBps, String description) {
    }

    /** The outcome of trying to collect: what the caller should tell the person who asked. */
    public record Collected(Invoice invoice, Charge charge) {
        public boolean paid() {
            return InvoiceRepository.PAID.equals(invoice.status());
        }
    }

    private final InvoiceRepository invoices;
    private final SubscriptionRepository subscriptions;
    private final BillingEvents events;
    private final NotificationLog notifications;
    private final PaymentsClient payments;
    private final BillingSettings settings;
    private final BillingProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoices, SubscriptionRepository subscriptions, BillingEvents events,
                          NotificationLog notifications, PaymentsClient payments, BillingSettings settings,
                          BillingProperties properties, TransactionTemplate tx, Clock clock) {
        this.invoices = invoices;
        this.subscriptions = subscriptions;
        this.events = events;
        this.notifications = notifications;
        this.payments = payments;
        this.settings = settings;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    // --- reading -----------------------------------------------------------------------------------

    public Invoice get(String merchantId, Boolean claimedMode, String id) {
        return invoices.find(id).filter(i -> i.merchantId().equals(merchantId))
                .filter(i -> claimedMode == null || claimedMode == i.livemode())
                .orElseThrow(() -> ApiException.notFound("invoice_not_found", "No such invoice"));
    }

    public List<Invoice> list(String merchantId, boolean livemode, String status, String customerId,
                              String subscriptionId, String startingAfter, int limit) {
        return invoices.page(merchantId, livemode, blank(status), blank(customerId), blank(subscriptionId),
                blank(startingAfter), Math.max(1, Math.min(limit, 100)) + 1);
    }

    public Optional<Invoice> byToken(String token) {
        return invoices.findByToken(token);
    }

    public List<InvoiceRepository.AttemptRow> attempts(String invoiceId) {
        return invoices.attempts(invoiceId);
    }

    // --- one-off invoices --------------------------------------------------------------------------

    public Invoice createDraft(String merchantId, boolean livemode, NewInvoice request) {
        var customer = payments.customer(merchantId, request.customerId());
        if (customer.livemode() != livemode) {
            throw ApiException.notFound("customer_not_found", "No such customer");
        }
        String currency = request.currency() == null ? "" : request.currency().toUpperCase(Locale.ROOT);
        if (!SupportedCurrencies.isSupported(currency)) {
            throw ApiException.invalidRequest("unsupported_currency", "Currency is not supported", "currency");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw ApiException.invalidRequest("lines_required", "An invoice needs at least one line", "lines");
        }
        String method = request.collectionMethod() == null ? "send_invoice" : request.collectionMethod();
        Instant now = clock.instant();
        List<Line> lines = new ArrayList<>();
        for (DraftLine line : request.lines()) {
            if (line.description() == null || line.description().isBlank()) {
                throw ApiException.invalidRequest("invalid_line", "Every line needs a description", "lines");
            }
            if (line.quantity() < 1 || line.unitAmount() == 0 || Math.abs(line.unitAmount()) > 99_999_999) {
                throw ApiException.invalidRequest("invalid_line", "A line needs a quantity and a non-zero amount", "lines");
            }
            lines.add(new Line(Ids.generate("il"), line.description().trim(), line.unitAmount(), line.quantity(),
                    Math.multiplyExact(line.unitAmount(), (long) line.quantity()), null, false, null, null));
        }
        int taxBps = request.taxRateBps() == null ? 0 : request.taxRateBps();
        Instant due = "send_invoice".equals(method)
                ? now.plus(Duration.ofDays(request.daysUntilDue() == null ? 14 : request.daysUntilDue())) : null;
        Totals totals = Totals.of(lines, taxBps);
        var invoice = new Invoice(Ids.generate("in"), merchantId, request.merchantName(), livemode, null,
                customer.id(), customer.email(), customer.name(), null, InvoiceRepository.DRAFT, "manual", method,
                currency, totals.subtotal(), totals.tax(), totals.total(), 0, taxBps, request.description(), null, null,
                null, null, newToken(), 0, null, null, null, due, now, null, null, null, now, lines);
        tx.executeWithoutResult(status -> invoices.insert(invoice));
        return invoices.find(invoice.id()).orElseThrow();
    }

    public Invoice finalizeInvoice(String merchantId, Boolean claimedMode, String id) {
        return tx.execute(status -> {
            Invoice invoice = lockOwned(merchantId, claimedMode, id);
            if (!InvoiceRepository.DRAFT.equals(invoice.status())) {
                throw ApiException.conflict("invoice_not_draft", "Only a draft invoice can be finalized", null);
            }
            if (invoice.total() <= 0) {
                throw ApiException.invalidRequest("invoice_total_not_positive", "An invoice must come to more than zero", null);
            }
            Instant now = clock.instant();
            invoices.finalizeDraft(id, invoices.nextNumber(merchantId, invoice.livemode()), invoice.dueDate(), now);
            Invoice finalized = invoices.find(id).orElseThrow();
            events.invoice("InvoiceFinalized", finalized);
            announce(finalized);
            return finalized;
        });
    }

    public void deleteDraft(String merchantId, Boolean claimedMode, String id) {
        tx.executeWithoutResult(status -> {
            Invoice invoice = lockOwned(merchantId, claimedMode, id);
            if (!InvoiceRepository.DRAFT.equals(invoice.status())) {
                throw ApiException.conflict("invoice_not_draft", "Only a draft invoice can be deleted", null);
            }
            invoices.deleteDraft(id);
        });
    }

    public Invoice voidInvoice(String merchantId, Boolean claimedMode, String id) {
        return tx.execute(status -> {
            Invoice invoice = lockOwned(merchantId, claimedMode, id);
            if (!InvoiceRepository.OPEN.equals(invoice.status()) && !InvoiceRepository.DRAFT.equals(invoice.status())) {
                throw ApiException.conflict("invoice_not_voidable", "Only an unpaid invoice can be voided", null);
            }
            Instant now = clock.instant();
            invoices.setStatus(id, InvoiceRepository.VOID, now);
            invoices.cancelPendingAttempts(id, now);
            Invoice voided = invoices.find(id).orElseThrow();
            events.invoice("InvoiceVoided", voided);
            return voided;
        });
    }

    // --- creating the invoice of a subscription ----------------------------------------------------

    /**
     * Writes an open, numbered invoice for a subscription period, taking along the lines that were waiting for it.
     * The caller holds the lock on the subscription and commits; the card is charged afterwards with {@link #collect}.
     */
    public Invoice createSubscriptionInvoice(Subscription sub, String reason, List<Line> periodLines, Instant periodStart,
                                             Instant periodEnd, boolean includePending) {
        Instant now = clock.instant();
        List<Line> lines = new ArrayList<>(periodLines);
        if (includePending) {
            for (PendingItem item : invoices.pending(sub.id())) {
                lines.add(new Line(Ids.generate("il"), item.description(), item.unitAmount(), item.quantity(),
                        item.amount(), item.priceId(), item.proration(), item.periodStart(), item.periodEnd()));
            }
        }
        Totals totals = Totals.of(lines, sub.taxRateBps());
        Instant due = "send_invoice".equals(sub.collectionMethod()) ? now.plus(Duration.ofDays(7)) : now;
        String id = Ids.generate("in");
        var invoice = new Invoice(id, sub.merchantId(), sub.merchantName(), sub.livemode(), sub.id(), sub.customerId(),
                sub.customerEmail(), sub.customerName(), invoices.nextNumber(sub.merchantId(), sub.livemode()),
                InvoiceRepository.OPEN, reason, sub.collectionMethod(), sub.currency(), totals.subtotal(), totals.tax(),
                Math.max(0, totals.total()), 0, sub.taxRateBps(), null, sub.defaultPaymentMethod(), null, null, null,
                newToken(), 0, null, periodStart, periodEnd, due, now, now, null, null, now, lines);
        invoices.insert(invoice);
        if (includePending) {
            invoices.claimPending(sub.id(), id);
        }
        Invoice stored = invoices.find(id).orElseThrow();
        events.invoice("InvoiceFinalized", stored);
        if (stored.total() == 0) {
            // Credits cover everything, so there is nothing to collect.
            return markPaid(stored, null);
        }
        return stored;
    }

    // --- collecting --------------------------------------------------------------------------------

    /**
     * Charges the saved card for an open invoice now. With {@code retrying} a failure is part of the dunning
     * process (the subscription goes past due and a retry is scheduled); without it a failure only comes back to
     * the caller, for example the merchant pressing "Charge now".
     */
    public Collected collect(String invoiceId, boolean retrying) {
        Invoice invoice = tx.execute(status -> {
            Invoice locked = invoices.lock(invoiceId).orElseThrow();
            if (!locked.open()) {
                return locked;
            }
            invoices.countAttempt(invoiceId, clock.instant());
            return invoices.find(invoiceId).orElseThrow();
        });
        if (!invoice.open()) {
            return new Collected(invoice, null);
        }

        Charge charge;
        try {
            charge = payments.chargeOffSession(invoice.merchantId(), invoice.livemode(), invoice.merchantName(),
                    invoice.customerId(), invoice.defaultPaymentMethod(), invoice.total(), invoice.currency(),
                    "Invoice " + invoice.number(), metadata(invoice));
        } catch (ApiException e) {
            log.warn("Could not charge invoice {}: {}", invoice.id(), e.code());
            charge = new Charge(null, null, null, "failed", e.code(), e.getMessage());
        }
        Charge outcome = charge;
        Invoice settled = tx.execute(status -> {
            Invoice locked = invoices.lock(invoiceId).orElseThrow();
            if (!locked.open()) {
                return locked; // paid meanwhile, for instance on the hosted page
            }
            if (outcome.succeeded()) {
                return markPaid(locked, outcome.paymentId());
            }
            return paymentFailed(locked, outcome.paymentId(), outcome.failureCode(), outcome.failureMessage(), retrying);
        });
        return new Collected(settled, outcome);
    }

    /** The retries that have come due. Each runs on its own so one invoice cannot hold up the others. */
    public int runDueRetries(int limit) {
        int done = 0;
        for (var attempt : invoices.dueAttempts(clock.instant(), limit)) {
            try {
                boolean claimed = Boolean.TRUE.equals(tx.execute(status ->
                        invoices.completeAttempt(attempt.id(), "started", clock.instant())));
                if (!claimed) {
                    continue;
                }
                Invoice due = invoices.find(attempt.invoiceId()).orElseThrow();
                boolean subscriptionEnded = due.subscriptionId() != null
                        && subscriptions.find(due.subscriptionId()).map(Subscription::ended).orElse(true);
                if (!due.open() || subscriptionEnded) {
                    tx.executeWithoutResult(status -> invoices.setAttemptResult(attempt.id(), "skipped"));
                    continue;
                }
                Collected result = collect(attempt.invoiceId(), true);
                String outcome = result.paid() ? "paid" : "failed";
                tx.executeWithoutResult(status -> invoices.setAttemptResult(attempt.id(), outcome));
                done++;
            } catch (RuntimeException e) {
                log.warn("Retry {} of invoice {} failed unexpectedly", attempt.attemptNo(), attempt.invoiceId(), e);
            }
        }
        return done;
    }


    // --- payment events ----------------------------------------------------------------------------

    /** A payment tagged with an invoice succeeded, wherever it was made. A no-op if the invoice is already paid. */
    public void onPaymentSucceeded(String invoiceId, String paymentId) {
        tx.executeWithoutResult(status -> invoices.lock(invoiceId).ifPresent(invoice -> {
            if (invoice.open() || InvoiceRepository.UNCOLLECTIBLE.equals(invoice.status())) {
                markPaid(invoice, paymentId);
            } else if (InvoiceRepository.PAID.equals(invoice.status()) && !paymentId.equals(invoice.paymentIntentId())) {
                log.warn("Invoice {} was paid again by {}: the customer was charged twice", invoiceId, paymentId);
            }
        }));
    }

    /**
     * An off-session payment for an invoice failed. Charges made by this service were already recorded when the
     * answer came back, so this only matters for a failure that happened while we were not looking.
     */
    public void onPaymentFailed(String invoiceId, String paymentId, String code) {
        tx.executeWithoutResult(status -> invoices.lock(invoiceId).ifPresent(invoice -> {
            if (invoice.open() && !paymentId.equals(invoice.lastFailedPaymentId())) {
                paymentFailed(invoice, paymentId, code, null, true);
            }
        }));
    }

    // --- internals ---------------------------------------------------------------------------------

    /** Pays the invoice and brings its subscription up to date. Runs inside the caller's transaction. */
    Invoice markPaid(Invoice invoice, String paymentId) {
        Instant now = clock.instant();
        invoices.markPaid(invoice.id(), invoice.total(), paymentId, now);
        invoices.cancelPendingAttempts(invoice.id(), now);
        Invoice paid = invoices.find(invoice.id()).orElseThrow();
        events.invoice("InvoicePaid", paid);
        notifications.send(paid.merchantId(), paid.livemode(), "invoice_paid", paid.customerEmail(),
                "Receipt for invoice " + paid.number(),
                "We received your payment of %s for invoice %s. Thank you.".formatted(money(paid.total(), paid.currency()),
                        paid.number()), paid.id());
        if (paid.subscriptionId() != null) {
            subscriptions.lock(paid.subscriptionId()).ifPresent(sub -> {
                if (sub.ended()) {
                    return;
                }
                boolean renewal = "subscription_cycle".equals(paid.billingReason());
                if (!SubscriptionRepository.ACTIVE.equals(sub.status())) {
                    subscriptions.setStatus(sub.id(), SubscriptionRepository.ACTIVE, now);
                }
                events.subscription(renewal ? "SubscriptionRenewed" : "SubscriptionUpdated",
                        subscriptions.find(sub.id()).orElseThrow());
            });
        }
        return paid;
    }

    /** Counts a failed payment, tells the customer and decides what happens next. Runs inside the caller's transaction. */
    Invoice paymentFailed(Invoice invoice, String paymentId, String code, String message, boolean retrying) {
        Instant now = clock.instant();
        invoices.recordFailure(invoice.id(), paymentId, code == null ? "payment_failed" : code, now);
        Invoice failed = invoices.find(invoice.id()).orElseThrow();
        events.invoice("InvoicePaymentFailed", failed);
        if (!retrying || failed.subscriptionId() == null) {
            return failed;
        }

        Settings rules = settings.get(failed.merchantId());
        int attempt = failed.attemptCount();
        String reason = reasonFor(code, message);
        Subscription sub = subscriptions.lock(failed.subscriptionId()).orElseThrow();
        if (!sub.ended()) {
            String next = SubscriptionRepository.INCOMPLETE.equals(sub.status()) ? SubscriptionRepository.INCOMPLETE
                    : SubscriptionRepository.PAST_DUE;
            if (!next.equals(sub.status())) {
                subscriptions.setStatus(sub.id(), next, now);
                if (SubscriptionRepository.PAST_DUE.equals(next)) {
                    events.subscription("SubscriptionPastDue", subscriptions.find(sub.id()).orElseThrow());
                }
            }
        }
        if (attempt <= rules.retryDays().size()) {
            Instant when = failed.firstFailedAt().plus(Duration.ofDays(rules.retryDays().get(attempt - 1)));
            invoices.scheduleAttempt(Ids.generate("dun"), failed.id(), attempt, when);
            notifications.send(failed.merchantId(), failed.livemode(), "payment_failed", failed.customerEmail(),
                    "Payment for invoice %s failed".formatted(failed.number()),
                    ("We could not take %s for invoice %s: %s. We will try again on %s. You can also pay now: %s")
                            .formatted(money(failed.total(), failed.currency()), failed.number(), reason, DATE.format(when),
                                    events.hostedUrl(failed)), failed.id());
        } else {
            giveUp(failed, sub, rules, reason, now);
        }
        return invoices.find(failed.id()).orElseThrow();
    }

    private void giveUp(Invoice invoice, Subscription sub, Settings rules, String reason, Instant now) {
        invoices.setStatus(invoice.id(), InvoiceRepository.UNCOLLECTIBLE, now);
        if (!sub.ended()) {
            if ("cancel".equals(rules.finalAction())) {
                subscriptions.end(sub.id(), now);
                events.subscription("SubscriptionCanceled", subscriptions.find(sub.id()).orElseThrow());
            } else {
                subscriptions.setStatus(sub.id(), SubscriptionRepository.UNPAID, now);
                events.subscription("SubscriptionUpdated", subscriptions.find(sub.id()).orElseThrow());
            }
        }
        notifications.send(invoice.merchantId(), invoice.livemode(), "subscription_canceled", invoice.customerEmail(),
                "cancel".equals(rules.finalAction()) ? "Your subscription was canceled" : "Your subscription is unpaid",
                ("We could not take payment for invoice %s after %d tries (%s), so your subscription %s. Pay the invoice to start again: %s")
                        .formatted(invoice.number(), invoice.attemptCount(), reason,
                                "cancel".equals(rules.finalAction()) ? "was canceled" : "was paused until you pay",
                                events.hostedUrl(invoice)), invoice.id());
    }

    /** Emails the customer a link to the hosted invoice page. */
    public void announce(Invoice invoice) {
        notifications.send(invoice.merchantId(), invoice.livemode(), "invoice_sent", invoice.customerEmail(),
                "Invoice %s from %s".formatted(invoice.number(), invoice.merchantName() == null ? "your merchant" : invoice.merchantName()),
                "Invoice %s for %s is ready. Pay it here: %s".formatted(invoice.number(),
                        money(invoice.total(), invoice.currency()), events.hostedUrl(invoice)), invoice.id());
    }

    private Invoice lockOwned(String merchantId, Boolean claimedMode, String id) {
        return invoices.lock(id).filter(i -> i.merchantId().equals(merchantId))
                .filter(i -> claimedMode == null || claimedMode == i.livemode())
                .orElseThrow(() -> ApiException.notFound("invoice_not_found", "No such invoice"));
    }

    private static Map<String, String> metadata(Invoice invoice) {
        return invoice.subscriptionId() == null
                ? Map.of("invoice_id", invoice.id())
                : Map.of("invoice_id", invoice.id(), "subscription_id", invoice.subscriptionId());
    }

    private static String reasonFor(String code, String message) {
        if ("authentication_required".equals(code)) {
            return "your bank needs you to confirm the payment";
        }
        if ("no_payment_method".equals(code) || "payment_rejected".equals(code) || "payment_method_not_found".equals(code)) {
            return "there is no card on file";
        }
        if (message != null && !message.isBlank()) {
            return message.endsWith(".") ? message.substring(0, message.length() - 1).toLowerCase(Locale.ROOT)
                    : message.toLowerCase(Locale.ROOT);
        }
        return "the payment was declined";
    }

    static String money(long amount, String currency) {
        return new BigDecimal(amount).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString() + " " + currency;
    }

    private static String newToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Subtotal, tax and total of a set of lines. Tax is rounded half up and never negative. */
    public record Totals(long subtotal, long tax, long total) {
        public static Totals of(List<Line> lines, int taxRateBps) {
            long subtotal = lines.stream().mapToLong(Line::amount).sum();
            long tax = subtotal <= 0 ? 0 : BigDecimal.valueOf(subtotal).multiply(BigDecimal.valueOf(taxRateBps))
                    .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
            return new Totals(subtotal, tax, subtotal + tax);
        }
    }
}
