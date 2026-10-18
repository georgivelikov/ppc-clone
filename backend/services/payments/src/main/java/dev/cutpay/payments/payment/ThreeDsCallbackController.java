package dev.cutpay.payments.payment;

import dev.cutpay.payments.customer.SetupIntentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoint used by the acquirer. The gateway has no route to {@code /internal/**},
 * so it is only reachable from inside the network.
 */
@RestController
class ThreeDsCallbackController {

    record Callback(@NotBlank String acquirerRef, @NotBlank String status, String declineCode, String message) {
    }

    private final PaymentService payments;
    private final SetupIntentService setups;

    ThreeDsCallbackController(PaymentService payments, SetupIntentService setups) {
        this.payments = payments;
        this.setups = setups;
    }

    /** The reference belongs either to a payment or to a card being saved. */
    @PostMapping("/internal/3ds/callback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void callback(@Valid @RequestBody Callback callback) {
        boolean approved = "approved".equals(callback.status());
        if (!setups.completeThreeDs(callback.acquirerRef(), approved, callback.declineCode(), callback.message())) {
            payments.completeThreeDs(callback.acquirerRef(), approved, callback.declineCode(), callback.message());
        }
    }
}
