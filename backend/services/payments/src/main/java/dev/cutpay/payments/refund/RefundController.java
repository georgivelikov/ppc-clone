package dev.cutpay.payments.refund;

import dev.cutpay.payments.payment.PaymentDtos.RefundView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RefundController {

    record CreateRefundRequest(@NotBlank String paymentIntent, @Min(1) Long amount, @Size(max = 200) String reason) {
    }

    record RefundList(List<RefundView> data) {
    }

    private final RefundService refunds;

    RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @PostMapping("/v1/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    RefundView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRefundRequest request) {
        return refunds.create(jwt.getClaimAsString("mid"),
                new RefundService.CreateRefund(request.paymentIntent(), request.amount(), request.reason()));
    }

    @GetMapping("/v1/refunds")
    RefundList list(@AuthenticationPrincipal Jwt jwt,
                    @RequestParam(name = "payment_intent", required = false) String paymentIntent,
                    @RequestParam(name = "starting_after", required = false) String startingAfter,
                    @RequestParam(defaultValue = "20") int limit) {
        return new RefundList(refunds.list(jwt.getClaimAsString("mid"), paymentIntent, startingAfter, limit));
    }
}
