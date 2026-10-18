package dev.cutpay.billing.invoice;

import dev.cutpay.billing.invoice.InvoiceRepository.AttemptRow;
import dev.cutpay.billing.invoice.InvoiceRepository.Invoice;
import dev.cutpay.billing.invoice.InvoiceRepository.Line;
import java.time.Instant;
import java.util.List;

/** The JSON shapes of invoices. */
public final class InvoiceViews {

    private InvoiceViews() {
    }

    public record LineView(String id, String description, long unitAmount, int quantity, long amount, String price,
                           boolean proration, Instant periodStart, Instant periodEnd) {
        public static LineView of(Line l) {
            return new LineView(l.id(), l.description(), l.unitAmount(), l.quantity(), l.amount(), l.priceId(),
                    l.proration(), l.periodStart(), l.periodEnd());
        }
    }

    public record AttemptView(int attemptNo, Instant scheduledAt, Instant executedAt, String result) {
        static AttemptView of(AttemptRow a) {
            return new AttemptView(a.attemptNo(), a.scheduledAt(), a.executedAt(), a.result());
        }
    }

    public record InvoiceView(String id, String object, boolean livemode, String number, String status,
                              String subscription, String customer, String customerEmail, String customerName,
                              String billingReason, String collectionMethod, String currency, long subtotal, long tax,
                              long creditApplied, long total, long amountPaid, long amountDue, String description,
                              Instant dueDate,
                              Instant periodStart, Instant periodEnd, String paymentIntent, String failureCode,
                              int attemptCount, Instant nextPaymentAttempt, String hostedInvoiceUrl, String invoicePdf,
                              List<LineView> lines, List<AttemptView> attempts, Instant createdAt, Instant finalizedAt,
                              Instant paidAt) {
    }

    public static InvoiceView view(Invoice i, String hostedUrl, List<AttemptRow> attempts) {
        Instant next = attempts == null ? null : attempts.stream().filter(a -> a.executedAt() == null)
                .map(AttemptRow::scheduledAt).findFirst().orElse(null);
        boolean unpaid = InvoiceRepository.OPEN.equals(i.status());
        return new InvoiceView(i.id(), "invoice", i.livemode(), i.number(), i.status(), i.subscriptionId(),
                i.customerId(), i.customerEmail(), i.customerName(), i.billingReason(), i.collectionMethod(),
                i.currency(), i.subtotal(), i.tax(), i.creditApplied(), i.total(), i.amountPaid(),
                unpaid ? i.total() - i.amountPaid() : 0,
                i.description(), i.dueDate(), i.periodStart(), i.periodEnd(), i.paymentIntentId(), i.failureCode(),
                i.attemptCount(), next, i.number() == null ? null : hostedUrl,
                i.number() == null ? null : "/v1/invoices/" + i.id() + "/pdf",
                i.lines().stream().map(LineView::of).toList(),
                attempts == null ? null : attempts.stream().map(AttemptView::of).toList(), i.createdAt(), i.finalizedAt(),
                i.paidAt());
    }
}
