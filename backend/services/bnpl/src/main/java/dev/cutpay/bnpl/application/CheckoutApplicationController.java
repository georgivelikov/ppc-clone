package dev.cutpay.bnpl.application;

import dev.cutpay.bnpl.application.ApplicationRepository.Application;
import dev.cutpay.bnpl.application.ApplicationService.Apply;
import dev.cutpay.bnpl.model.Applicant;
import dev.cutpay.bnpl.model.ProviderCatalog;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The customer at the checkout applying for instalments and watching the answer. Authorised by the client secret. */
@RestController
class CheckoutApplicationController {

    record ApplyRequest(@NotBlank String provider, @NotBlank String plan, String country, Applicant applicant) {
    }

    /** What the customer may see: their own plan and where the application stands, not what the merchant pays. */
    record CustomerView(String object, String id, String status, String provider, String providerName, String plan,
                        int instalments, List<Long> instalmentAmounts, int intervalDays, double apr, long totalPayable,
                        boolean awaitingDecision, String message) {
        static CustomerView of(Application a) {
            return new CustomerView("bnpl_application", a.id(), a.status().wire(), a.provider(),
                    ProviderCatalog.find(a.provider()).map(p -> p.name()).orElse(a.provider()), a.plan(), a.instalmentCount(),
                    a.instalments(), a.intervalDays(), a.aprPercent(), a.totalPayable(), a.status().isOpen(), message(a));
        }

        private static String message(Application a) {
            return switch (a.status()) {
                case CREATED, PENDING_REVIEW -> "The provider is looking at your application. This can take a moment.";
                case APPROVED, SETTLED -> "Your application was approved.";
                case DECLINED -> "The provider could not approve this application. You can choose another option.";
                case FAILED -> "The provider could not be reached. You can choose another option.";
                case EXPIRED -> "The application took too long and was closed. You can try again.";
                case CANCELED -> "The application was canceled.";
            };
        }
    }

    /** Ask for the best provider; with `fallback` the next best ones may be tried after a decline or a failure. */
    record BestRequest(String country, Applicant applicant, boolean fallback) {
    }

    record Attempt(String id, String provider, String providerName, String status) {
    }

    /** The last try is the outcome; the tries before it were declined or failed. */
    record BestView(String object, CustomerView application, List<Attempt> attempts) {
    }

    private final ApplicationService service;

    CheckoutApplicationController(ApplicationService service) {
        this.service = service;
    }

    @PostMapping("/checkout/{clientSecret}/bnpl/apply")
    @ResponseStatus(HttpStatus.CREATED)
    CustomerView apply(@PathVariable String clientSecret, @RequestBody @jakarta.validation.Valid ApplyRequest request) {
        return CustomerView.of(service.apply(clientSecret,
                new Apply(request.provider(), request.plan(), request.applicant(), request.country())));
    }

    @PostMapping("/checkout/{clientSecret}/bnpl/apply_best")
    @ResponseStatus(HttpStatus.CREATED)
    BestView applyBest(@PathVariable String clientSecret, @RequestBody BestRequest request) {
        var result = service.applyBest(clientSecret, request.applicant(), request.country(), request.fallback());
        return new BestView("bnpl_best", CustomerView.of(result.last()), result.attempts().stream()
                .map(a -> new Attempt(a.id(), a.provider(), ProviderCatalog.find(a.provider()).map(p -> p.name()).orElse(a.provider()),
                        a.status().wire())).toList());
    }

    @GetMapping("/checkout/{clientSecret}/bnpl/applications/{id}")
    CustomerView get(@PathVariable String clientSecret, @PathVariable String id) {
        return CustomerView.of(service.forCheckout(clientSecret, id));
    }

    @PostMapping("/checkout/{clientSecret}/bnpl/applications/{id}/cancel")
    CustomerView cancel(@PathVariable String clientSecret, @PathVariable String id) {
        return CustomerView.of(service.cancelByCustomer(clientSecret, id));
    }
}
