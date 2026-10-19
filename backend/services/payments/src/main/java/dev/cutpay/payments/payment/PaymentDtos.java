package dev.cutpay.payments.payment;

import dev.cutpay.payments.domain.PaymentAttempt;
import dev.cutpay.payments.domain.PaymentIntent;
import dev.cutpay.payments.domain.Refund;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
            @Size(max = 160) String merchantName,
            @Pattern(regexp = "automatic|manual", message = "Capture method must be automatic or manual")
            String captureMethod,
            @Size(max = 64) String customer,
            @Size(max = 64) String paymentMethod,
            Boolean offSession,
            @Pattern(regexp = "off_session", message = "Setup future usage can only be off_session")
            String setupFutureUsage,
            @Valid List<TransferInput> transferData,
            @Min(value = 0, message = "Application fee cannot be negative") Long applicationFeeAmount) {

        /** A payment for a saved customer, charged straight away to a saved card. */
        public boolean chargesSavedCard() {
            return Boolean.TRUE.equals(offSession);
        }

        public CreatePaymentRequest withMerchantName(String name) {
            return new CreatePaymentRequest(amount, currency, description, customerEmail, metadata, returnUrl, name,
                    captureMethod, customer, paymentMethod, offSession, setupFutureUsage, transferData,
                    applicationFeeAmount);
        }
    }

    /** Part of a payment sent to a connected account. Omit the amount to send everything the platform does not keep. */
    public record TransferInput(@NotBlank String destination, @Min(value = 1, message = "Amount must be positive") Long amount) {
    }

    public record TransferView(String destination, long amount) {
    }

    /** Omit the amount to take everything that was authorised. */
    public record CaptureRequest(@Min(value = 1, message = "Amount must be positive") Long amountToCapture) {
    }

    /**
     * {@code method} is {@code card} (the default; a wallet is a card token handed out by the wallet) or {@code blik},
     * which takes a six digit {@code blik_code} instead of a token.
     */
    public record ConfirmRequest(@Size(max = 200) String token, @Size(max = 254) String email, @Size(max = 20) String method,
                                 @Size(max = 6) String blikCode) {

        public ConfirmRequest(String token, String email) {
            this(token, email, null, null);
        }

        public boolean isBlik() {
            return "blik".equals(method);
        }
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
                              String status, String captureMethod, String channel, String description, String customerEmail,
                              Map<String, String> metadata, String clientSecret, String returnUrl,
                              long amountRefunded, Long amountAuthorized, Instant captureBefore, String customer,
                              String paymentMethod, boolean offSession, String failureCode,
                              String failureMessage, Instant createdAt, Instant updatedAt,
                              List<AttemptView> attempts, List<RefundView> refunds, Object latestAttempt,
                              Long applicationFeeAmount, List<TransferView> transferData, String paymentMethodType,
                              BnplView bnpl) {

        /** The same payment with the parts of it sent to connected accounts, which only a single payment shows. */
        PaymentView withTransfers(List<TransferView> transfers) {
            return new PaymentView(id, object, livemode, amount, currency, status, captureMethod, channel, description,
                    customerEmail, metadata, clientSecret, returnUrl, amountRefunded, amountAuthorized, captureBefore,
                    customer, paymentMethod, offSession, failureCode, failureMessage, createdAt, updatedAt, attempts,
                    refunds, latestAttempt, applicationFeeAmount, transfers, paymentMethodType, bnpl);
        }

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
                    p.getStatus().wire(), p.getCaptureMethod(), p.getChannel(), p.getDescription(), p.getCustomerEmail(),
                    p.getMetadata(), p.getClientSecret(), p.getReturnUrl(), p.getAmountRefunded(),
                    p.getAmountAuthorized(), p.getCaptureBefore(), p.getCustomerId(), p.getPaymentMethodId(),
                    p.isOffSession(), p.getFailureCode(), p.getFailureMessage(),
                    p.getCreatedAt(), p.getUpdatedAt(),
                    attempts.stream().map(AttemptView::of).toList(),
                    refunds.stream().map(RefundView::of).toList(), latest, p.getApplicationFeeAmount(), null,
                    p.getPaymentMethodType(), BnplView.of(p));
        }
    }

    /** How a payment financed in instalments was settled: by whom, which application, and what the provider kept. */
    public record BnplView(String provider, String application, long fee) {
        static BnplView of(PaymentIntent p) {
            return p.getBnplApplicationId() == null ? null
                    : new BnplView(p.getBnplProvider(), p.getBnplApplicationId(), p.getBnplFee() == null ? 0 : p.getBnplFee());
        }
    }

    public record PaymentList(String object, List<PaymentView> data, boolean hasMore) {
    }

    public record NextAction(String type, String challengeId) {
    }

    /** What the hosted checkout is allowed to see. */
    public record SessionView(String id, String status, long amount, String currency, String description,
                              String merchantName, String returnUrl, String failureCode, String failureMessage,
                              NextAction nextAction, boolean saveCard) {
    }

    public record VolumeView(String currency, long count, long volume) {
    }

    public record SummaryView(String since, List<VolumeView> today) {
    }
}
