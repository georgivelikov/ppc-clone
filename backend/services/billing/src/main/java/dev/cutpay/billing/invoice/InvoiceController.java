package dev.cutpay.billing.invoice;

import dev.cutpay.billing.config.BillingProperties;
import dev.cutpay.billing.invoice.InvoiceRepository.Invoice;
import dev.cutpay.billing.invoice.InvoiceService.DraftLine;
import dev.cutpay.billing.invoice.InvoiceService.NewInvoice;
import dev.cutpay.billing.invoice.InvoiceViews.InvoiceView;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The merchant's side of invoices: create a one-off invoice, finalize it, charge it, void it, download the PDF. */
@RestController
class InvoiceController {

    record LineRequest(@NotBlank @Size(max = 300) String description, long unitAmount, @Min(1) int quantity) {
    }

    record CreateInvoice(@NotBlank String customer, @NotBlank String currency, @NotEmpty @Valid List<LineRequest> lines,
                         String collectionMethod, @Min(0) Integer daysUntilDue, Double taxRatePercent,
                         @Size(max = 500) String description, @Size(max = 160) String merchantName) {
    }

    record InvoiceList(String object, List<InvoiceView> data, boolean hasMore) {
    }

    /** What happened when the card was charged: the invoice as it is now, and why it failed if it did. */
    record PayResult(InvoiceView invoice, boolean paid, String failureCode, String failureMessage) {
    }

    private final InvoiceService service;
    private final BillingProperties properties;
    private final InvoicePdf pdf;

    InvoiceController(InvoiceService service, BillingProperties properties, InvoicePdf pdf) {
        this.service = service;
        this.properties = properties;
        this.pdf = pdf;
    }

    @PostMapping("/v1/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceView create(@AuthenticationPrincipal Jwt jwt,
                       @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                       @Valid @RequestBody CreateInvoice request) {
        if (request.collectionMethod() != null && !List.of("charge_automatically", "send_invoice").contains(request.collectionMethod())) {
            throw ApiException.invalidRequest("invalid_collection_method",
                    "Collection method must be charge_automatically or send_invoice", "collection_method");
        }
        int taxBps = request.taxRatePercent() == null ? 0 : (int) Math.round(request.taxRatePercent() * 100);
        if (taxBps < 0 || taxBps > 10_000) {
            throw ApiException.invalidRequest("invalid_tax_rate", "Tax rate must be between 0 and 100", "tax_rate_percent");
        }
        Invoice invoice = service.createDraft(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode),
                new NewInvoice(request.customer(), request.merchantName(), request.currency(),
                        request.lines().stream().map(l -> new DraftLine(l.description(), l.unitAmount(), l.quantity())).toList(),
                        request.collectionMethod(), request.daysUntilDue(), taxBps, request.description()));
        return view(invoice, false);
    }

    @GetMapping("/v1/invoices")
    InvoiceList list(@AuthenticationPrincipal Jwt jwt,
                     @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                     @RequestParam(required = false) String status, @RequestParam(required = false) String customer,
                     @RequestParam(required = false) String subscription,
                     @RequestParam(name = "starting_after", required = false) String startingAfter,
                     @RequestParam(defaultValue = "20") int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        List<Invoice> found = service.list(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode), status,
                customer, subscription, startingAfter, size);
        return new InvoiceList("list", found.stream().limit(size).map(i -> view(i, false)).toList(), found.size() > size);
    }

    @GetMapping("/v1/invoices/{id}")
    InvoiceView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return view(service.get(merchant(jwt), claim(jwt), id), true);
    }

    @PostMapping("/v1/invoices/{id}/finalize")
    InvoiceView finalizeInvoice(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return view(service.finalizeInvoice(merchant(jwt), claim(jwt), id), true);
    }

    /** Charges the saved card of the customer now. A card that is refused is an answer, not an error. */
    @PostMapping("/v1/invoices/{id}/pay")
    PayResult pay(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        Invoice invoice = service.get(merchant(jwt), claim(jwt), id);
        if (!invoice.open()) {
            throw ApiException.conflict("invoice_not_open", "Only an open invoice can be paid", null);
        }
        var result = service.collect(id, false);
        var charge = result.charge();
        return new PayResult(view(result.invoice(), true), result.paid(), charge == null ? null : charge.failureCode(),
                charge == null ? null : charge.failureMessage());
    }

    @PostMapping("/v1/invoices/{id}/void")
    InvoiceView voidInvoice(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return view(service.voidInvoice(merchant(jwt), claim(jwt), id), true);
    }

    @DeleteMapping("/v1/invoices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        service.deleteDraft(merchant(jwt), claim(jwt), id);
    }

    @GetMapping(value = "/v1/invoices/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> pdf(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        Invoice invoice = service.get(merchant(jwt), claim(jwt), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName(invoice) + "\"")
                .contentType(MediaType.APPLICATION_PDF).body(pdf.render(invoice));
    }

    static String fileName(Invoice invoice) {
        return (invoice.number() == null ? invoice.id() : invoice.number()) + ".pdf";
    }

    private InvoiceView view(Invoice invoice, boolean detailed) {
        return InvoiceViews.view(invoice, properties.checkoutUrl() + "/i/" + invoice.hostedToken(),
                detailed ? service.attempts(invoice.id()) : null);
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }

    private static Boolean claim(Jwt jwt) {
        return jwt.getClaimAsBoolean("livemode");
    }
}
