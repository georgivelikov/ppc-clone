package dev.cutpay.billing.invoice;

import dev.cutpay.billing.config.BillingProperties;
import dev.cutpay.billing.invoice.InvoiceRepository.Invoice;
import dev.cutpay.billing.invoice.InvoiceViews.LineView;
import dev.cutpay.billing.payments.PaymentsClient;
import dev.cutpay.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a customer sees when they open the link of an invoice. The unguessable token in the address is the only
 * credential, like the client secret of a payment, and the gateway rate-limits these routes.
 */
@RestController
class PublicInvoiceController {

    record PublicInvoice(String number, String status, String merchantName, String customerName, String customerEmail,
                         String currency, long subtotal, long tax, long total, long amountPaid, String description,
                         Instant dueDate, Instant paidAt, List<LineView> lines, boolean payable) {
    }

    record PayLink(String url) {
    }

    private final InvoiceService service;
    private final PaymentsClient payments;
    private final BillingProperties properties;
    private final InvoicePdf pdf;

    PublicInvoiceController(InvoiceService service, PaymentsClient payments, BillingProperties properties, InvoicePdf pdf) {
        this.service = service;
        this.payments = payments;
        this.properties = properties;
        this.pdf = pdf;
    }

    @GetMapping("/invoices/{token}")
    PublicInvoice get(@PathVariable String token) {
        Invoice i = visible(token);
        return new PublicInvoice(i.number(), i.status(), i.merchantName(), i.customerName(), i.customerEmail(),
                i.currency(), i.subtotal(), i.tax(), i.total(), i.amountPaid(), i.description(), i.dueDate(), i.paidAt(),
                i.lines().stream().map(LineView::of).toList(), i.open());
    }

    /** Starts a payment for the invoice on the hosted checkout. Whatever card the customer uses, the invoice is settled by the payment event. */
    @PostMapping("/invoices/{token}/pay")
    PayLink pay(@PathVariable String token) {
        Invoice i = visible(token);
        if (!i.open()) {
            throw ApiException.conflict("invoice_not_open", "This invoice can no longer be paid", null);
        }
        var charge = payments.createCheckout(i.merchantId(), i.livemode(), i.merchantName(), i.customerId(),
                i.customerEmail(), i.total(), i.currency(), "Invoice " + i.number(), Map.of("invoice_id", i.id()),
                properties.checkoutUrl() + "/i/" + token + "?returned=1");
        return new PayLink(charge.url());
    }

    @GetMapping(value = "/invoices/{token}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> pdf(@PathVariable String token) {
        Invoice i = visible(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + InvoiceController.fileName(i) + "\"")
                .contentType(MediaType.APPLICATION_PDF).body(pdf.render(i));
    }

    /** Drafts are the merchant's work in progress and are never shown to the customer. */
    private Invoice visible(String token) {
        return service.byToken(token).filter(i -> !InvoiceRepository.DRAFT.equals(i.status()))
                .orElseThrow(() -> ApiException.notFound("invoice_not_found", "This invoice link is not valid"));
    }
}
