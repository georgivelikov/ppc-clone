package dev.cutpay.payments.payment;

import dev.cutpay.payments.domain.PaymentAttempt;
import dev.cutpay.payments.domain.PaymentIntent;
import dev.cutpay.payments.domain.Refund;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreatePaymentRequest(
            @Min(value = 1, message = "Amount must be positive") @Max(99_999_999) long amount,
            @NotBlank String currency,
            @Size(max = 500) String description,
            @Size(max = 254) String customerEmail,
            Map<String, String> metadata,
            @Size(max = 2000) String returnUrl,
            @Size(max = 160) String merchantName) {
    }

    public record ConfirmRequest(@NotBlank String token, @Size(max = 254) String email) {
    }

    public record CheckoutSessionCreated(String paymentId, String clientSecret, String url) {
    }

    public record AttemptView(String id, String methodType, String cardBrand, String cardLast4, Integer cardExpMonth,
                              Integer cardExpYear, String status, String declineCode, String threeDsStatus,
                              Instant createdAt) {
        static AttemptView of(PaymentAttempt a) {
            return new AttemptView(a.getId(), a.getMethodType(), a.getCardBrand(), a.getCardLast4(),
                    a.getCardExpMonth(), a.getCardExpYear(), a.getStatus(), a.getDeclineCode(), a.getThreeDsStatus(),
                    a.getCreatedAt());
        }
    }

    public record RefundView(String id, String object, String paymentIntent, long amount, String currency,
                             String reason, String status, String failureCode, Instant createdAt) {
        public static RefundView of(Refund r) {
            return new RefundView(r.getId(), "refund", r.getPaymentIntentId(), r.getAmount(), r.getCurrency(), r.getReason(),
                    r.getStatus(), r.getFailureCode(), r.getCreatedAt());
        }
    }

    public record PaymentView(String id, String object, boolean livemode, long amount, String currency,
                              String status, String captureMethod, String description, String customerEmail,
                              Map<String, String> metadata, String clientSecret, String returnUrl,
                              long amountRefunded, String failureCode, String failureMessage, Instant createdAt,
                              Instant updatedAt, List<AttemptView> attempts, List<RefundView> refunds,
                              Object latestAttempt) {

        static PaymentView of(PaymentIntent p, List<PaymentAttempt> attempts, List<Refund> refunds) {
            return of(p, attempts, refunds, false);
        }

        /**
         * {@code latestAttempt} is the id of the newest attempt, or the whole attempt when the caller asked
         * for {@code expand[]=latest_attempt}.
         */
        static PaymentView of(PaymentIntent p, List<PaymentAttempt> attempts, List<Refund> refunds,
                              boolean expandLatestAttempt) {
            Object latest = attempts.isEmpty() ? null
                    : expandLatestAttempt ? AttemptView.of(attempts.get(attempts.size() - 1))
                    : attempts.get(attempts.size() - 1).getId();
            return new PaymentView(p.getId(), "payment_intent", p.isLivemode(), p.getAmount(), p.getCurrency(),
                    p.getStatus().wire(), p.getCaptureMethod(), p.getDescription(), p.getCustomerEmail(),
                    p.getMetadata(), p.getClientSecret(), p.getReturnUrl(), p.getAmountRefunded(),
                    p.getFailureCode(), p.getFailureMessage(), p.getCreatedAt(), p.getUpdatedAt(),
                    attempts.stream().map(AttemptView::of).toList(),
                    refunds.stream().map(RefundView::of).toList(), latest);
        }
    }

    public record PaymentList(String object, List<PaymentView> data, boolean hasMore) {
    }

    public record NextAction(String type, String challengeId) {
    }

    /** What the hosted checkout is allowed to see. */
    public record SessionView(String id, String status, long amount, String currency, String description,
                              String merchantName, String returnUrl, String failureCode, String failureMessage,
                              NextAction nextAction) {
    }

    public record VolumeView(String currency, long count, long volume) {
    }

    public record SummaryView(String since, List<VolumeView> today) {
    }
}
