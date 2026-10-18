package dev.cutpay.payments.domain;

import dev.cutpay.web.ApiException;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public class IllegalTransitionException extends ApiException {

    public IllegalTransitionException(PaymentStatus from, PaymentStatus to) {
        this(from.wire(), to.wire(), "payment");
    }

    /** For other state machines (setup intents), whose states are not {@link PaymentStatus}. */
    public IllegalTransitionException(String from, String to) {
        this(from.toLowerCase(Locale.ROOT), to.toLowerCase(Locale.ROOT), "setup");
    }

    private IllegalTransitionException(String from, String to, String what) {
        super(HttpStatus.CONFLICT, "invalid_request_error", "invalid_state",
                "A %s in status %s cannot move to %s".formatted(what, from, to), null);
    }
}
