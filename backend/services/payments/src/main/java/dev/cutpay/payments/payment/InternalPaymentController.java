package dev.cutpay.payments.payment;

import dev.cutpay.payments.domain.PaymentIntent;
import dev.cutpay.payments.payment.PaymentDtos.CreatePaymentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service: lets the {@code links} service start a payment on behalf of a merchant. The gateway
 * does not route {@code /internal/**}, so it is reachable only inside the platform network.
 */
@RestController
class InternalPaymentController {

    record CreateForMerchant(@NotBlank String merchantId, boolean livemode, @NotBlank String merchantName,
                             @Valid CreatePaymentRequest payment) {
    }

    record Created(String paymentId, String clientSecret, String url) {
    }

    private final PaymentService payments;
    private final String checkoutUrl;

    InternalPaymentController(PaymentService payments, @Value("${cutpay.checkout-url}") String checkoutUrl) {
        this.payments = payments;
        this.checkoutUrl = checkoutUrl;
    }

    @PostMapping("/internal/payments")
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@Valid @RequestBody CreateForMerchant request) {
        var payment = request.payment();
        PaymentIntent intent = payments.create(request.merchantId(), request.livemode(), new CreatePaymentRequest(
                payment.amount(), payment.currency(), payment.description(), payment.customerEmail(),
                payment.metadata(), payment.returnUrl(), request.merchantName()));
        return new Created(intent.getId(), intent.getClientSecret(), checkoutUrl + "/c/" + intent.getClientSecret());
    }
}
