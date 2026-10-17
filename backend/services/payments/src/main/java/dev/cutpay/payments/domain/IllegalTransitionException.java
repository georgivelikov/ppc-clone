package dev.cutpay.payments.domain;

import dev.cutpay.web.ApiException;
import org.springframework.http.HttpStatus;

public class IllegalTransitionException extends ApiException {

    public IllegalTransitionException(PaymentStatus from, PaymentStatus to) {
        super(HttpStatus.CONFLICT, "invalid_request_error", "invalid_state",
                "A payment in status %s cannot move to %s".formatted(from.wire(), to.wire()), null);
    }
}
