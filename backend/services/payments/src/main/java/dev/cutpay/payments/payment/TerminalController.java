package dev.cutpay.payments.payment;

import dev.cutpay.payments.domain.PaymentIntent;
import dev.cutpay.payments.payment.PaymentDtos.CreatePaymentRequest;
import dev.cutpay.payments.payment.PaymentDtos.PaymentView;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The virtual POS terminal: a merchant keys in an order taken by phone or mail. The charge is a normal payment
 * with {@code channel = moto}; the card is then entered in the same iframe element as any other checkout and
 * the acquirer does not ask for 3-D Secure (docs/adr/0014-links-qr-and-virtual-pos.md).
 */
@RestController
class TerminalController {

    private final PaymentService payments;

    TerminalController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/v1/terminal/charges")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentView charge(@AuthenticationPrincipal Jwt jwt,
                       @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                       @Valid @RequestBody CreatePaymentRequest request) {
        String merchant = jwt.getClaimAsString("mid");
        Boolean claim = jwt.getClaimAsBoolean("livemode");
        PaymentIntent intent = payments.create(merchant, RequestMode.resolve(claim, requestedLivemode), request,
                PaymentIntent.CHANNEL_MOTO);
        return payments.get(merchant, claim, intent.getId(), Set.of());
    }
}
