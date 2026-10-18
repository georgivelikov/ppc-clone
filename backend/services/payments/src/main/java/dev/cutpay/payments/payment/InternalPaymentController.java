package dev.cutpay.payments.payment;

import dev.cutpay.payments.customer.CustomerService;
import dev.cutpay.payments.domain.Customer;
import dev.cutpay.payments.domain.PaymentIntent;
import dev.cutpay.payments.payment.PaymentDtos.CreatePaymentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service: lets the {@code links} and {@code billing} services act for a merchant. The gateway does not
 * route {@code /internal/**}, so it is reachable only inside the platform network.
 */
@RestController
class InternalPaymentController {

    record CreateForMerchant(@NotBlank String merchantId, boolean livemode, @NotBlank String merchantName,
                             @Valid CreatePaymentRequest payment) {
    }

    /** {@code status} is the outcome: an off-session payment is charged before this answer is sent. */
    record Created(String paymentId, String clientSecret, String url, String status, String failureCode,
                   String failureMessage) {
    }

    record CustomerView(String id, boolean livemode, String email, String name, String defaultPaymentMethod) {
    }

    private final PaymentService payments;
    private final CustomerService customers;
    private final String checkoutUrl;

    InternalPaymentController(PaymentService payments, CustomerService customers,
                              @Value("${cutpay.checkout-url}") String checkoutUrl) {
        this.payments = payments;
        this.customers = customers;
        this.checkoutUrl = checkoutUrl;
    }

    @PostMapping("/internal/payments")
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@Valid @RequestBody CreateForMerchant request) {
        PaymentIntent intent = payments.createAndCharge(request.merchantId(), request.livemode(),
                request.payment().withMerchantName(request.merchantName()));
        return new Created(intent.getId(), intent.getClientSecret(), checkoutUrl + "/c/" + intent.getClientSecret(),
                intent.getStatus().wire(), intent.getFailureCode(), intent.getFailureMessage());
    }

    /** What the BNPL aggregator needs to know about the payment a customer is about to pay in instalments. */
    @GetMapping("/internal/checkout/{clientSecret}")
    PaymentService.CheckoutFacts checkout(@PathVariable String clientSecret) {
        return payments.checkoutFacts(clientSecret);
    }

    /** What billing needs to know about a customer before it bills them. */
    @GetMapping("/internal/merchants/{merchantId}/customers/{customerId}")
    CustomerView customer(@PathVariable String merchantId, @PathVariable String customerId) {
        Customer customer = customers.find(merchantId, null, customerId);
        return new CustomerView(customer.getId(), customer.isLivemode(), customer.getEmail(), customer.getName(),
                customer.getDefaultPaymentMethod());
    }
}
