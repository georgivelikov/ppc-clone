package dev.cutpay.payments.payment;

import dev.cutpay.payments.domain.PaymentIntent;
import dev.cutpay.payments.payment.PaymentDtos.CheckoutSessionCreated;
import dev.cutpay.payments.payment.PaymentDtos.ConfirmRequest;
import dev.cutpay.payments.payment.PaymentDtos.CreatePaymentRequest;
import dev.cutpay.payments.payment.PaymentDtos.PaymentList;
import dev.cutpay.payments.payment.PaymentDtos.PaymentView;
import dev.cutpay.payments.payment.PaymentDtos.SessionView;
import dev.cutpay.payments.payment.PaymentDtos.SummaryView;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PaymentController {

    private final PaymentService payments;
    private final String checkoutUrl;

    PaymentController(PaymentService payments, @Value("${cutpay.checkout-url}") String checkoutUrl) {
        this.payments = payments;
        this.checkoutUrl = checkoutUrl;
    }

    @PostMapping("/v1/payment_intents")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreatePaymentRequest request) {
        PaymentIntent intent = payments.create(merchant(jwt), livemode(jwt), request);
        return payments.get(merchant(jwt), livemode(jwt), intent.getId());
    }

    /** Creates a payment and returns the hosted checkout URL to send the customer to. */
    @PostMapping("/v1/checkout_sessions")
    @ResponseStatus(HttpStatus.CREATED)
    CheckoutSessionCreated createSession(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody CreatePaymentRequest request) {
        PaymentIntent intent = payments.create(merchant(jwt), livemode(jwt), request);
        return new CheckoutSessionCreated(intent.getId(), intent.getClientSecret(),
                checkoutUrl + "/c/" + intent.getClientSecret());
    }

    @GetMapping("/v1/payment_intents")
    PaymentList list(@AuthenticationPrincipal Jwt jwt,
                     @RequestParam(required = false) String status,
                     @RequestParam(name = "q", required = false) String query,
                     @RequestParam(required = false) Instant from,
                     @RequestParam(required = false) Instant to,
                     @RequestParam(name = "min_amount", required = false) Long minAmount,
                     @RequestParam(name = "max_amount", required = false) Long maxAmount,
                     @RequestParam(name = "starting_after", required = false) String startingAfter,
                     @RequestParam(defaultValue = "20") int limit) {
        return payments.list(merchant(jwt), livemode(jwt), new PaymentService.Filter(status, query, from, to, minAmount, maxAmount,
                startingAfter, limit));
    }

    @GetMapping("/v1/payment_intents/{id}")
    PaymentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return payments.get(merchant(jwt), livemode(jwt), id);
    }

    @PostMapping("/v1/payment_intents/{id}/cancel")
    PaymentView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return payments.cancel(merchant(jwt), livemode(jwt), id);
    }

    @GetMapping("/v1/payment_stats")
    SummaryView stats(@AuthenticationPrincipal Jwt jwt) {
        return payments.todaysVolume(merchant(jwt), livemode(jwt));
    }

    // --- public: the hosted checkout, authorised by knowing the client secret ----------------------

    @GetMapping("/checkout/sessions/{clientSecret}")
    SessionView session(@PathVariable String clientSecret) {
        return payments.session(clientSecret);
    }

    @PostMapping("/checkout/sessions/{clientSecret}/confirm")
    SessionView confirm(@PathVariable String clientSecret, @Valid @RequestBody ConfirmRequest request) {
        return payments.confirm(clientSecret, request);
    }

    /** Dashboard sessions carry no mode and act on test data; API keys carry the mode of the key. */
    private static boolean livemode(Jwt jwt) {
        return Boolean.TRUE.equals(jwt.getClaimAsBoolean("livemode"));
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }
}
