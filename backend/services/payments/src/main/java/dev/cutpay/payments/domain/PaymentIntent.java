package dev.cutpay.payments.domain;

import dev.cutpay.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

    public static final String CHANNEL_ONLINE = "online";
    public static final String CHANNEL_MOTO = "moto";
    public static final String CAPTURE_AUTOMATIC = "automatic";
    public static final String CAPTURE_MANUAL = "manual";

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(nullable = false)
    private boolean livemode;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "capture_method", nullable = false)
    private String captureMethod;

    private String description;

    @Column(name = "customer_email")
    private String customerEmail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> metadata;

    @Column(name = "client_secret", nullable = false, updatable = false)
    private String clientSecret;

    @Column(name = "return_url")
    private String returnUrl;

    @Column(name = "amount_refunded", nullable = false)
    private long amountRefunded;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    /** {@code online} for hosted checkout and the API, {@code moto} for orders keyed in by the merchant. */
    @Column(nullable = false)
    private String channel;

    /** What was held on the card for a manual-capture payment; {@code amount} is what was taken once captured. */
    @Column(name = "amount_authorized")
    private Long amountAuthorized;

    /** After this moment an uncaptured authorisation is released by a job. */
    @Column(name = "capture_before")
    private Instant captureBefore;

    /** What the platform keeps of a split payment on top of the processing fee, if it said so. */
    @Column(name = "application_fee_amount")
    private Long applicationFeeAmount;

    /** Set when the payment was made for a saved customer. */
    @Column(name = "customer_id")
    private String customerId;

    /** The saved card this payment is charged to, if any. */
    @Column(name = "payment_method_id")
    private String paymentMethodId;

    /** Charged without the customer present: no 3-D Secure challenge is possible. */
    @Column(name = "off_session", nullable = false)
    private boolean offSession;

    /** {@code off_session}: save the card used here to the customer once the payment goes through. */
    @Column(name = "setup_future_usage")
    private String setupFutureUsage;

    /** How the payment was settled: {@code card}, or {@code bnpl} when a provider financed it in instalments. */
    @Column(name = "payment_method_type", nullable = false)
    private String paymentMethodType = "card";

    @Column(name = "bnpl_provider")
    private String bnplProvider;

    @Column(name = "bnpl_application_id")
    private String bnplApplicationId;

    /** What the BNPL provider keeps of the payment. */
    @Column(name = "bnpl_fee")
    private Long bnplFee;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentIntent() {
    }

    public PaymentIntent(String id, String clientSecret, String merchantId, String merchantName, boolean livemode,
                         Money money, String description, String customerEmail, Map<String, String> metadata,
                         String returnUrl) {
        this(id, clientSecret, merchantId, merchantName, livemode, money, description, customerEmail, metadata,
                returnUrl, CHANNEL_ONLINE);
    }

    public PaymentIntent(String id, String clientSecret, String merchantId, String merchantName, boolean livemode,
                         Money money, String description, String customerEmail, Map<String, String> metadata,
                         String returnUrl, String channel) {
        this(id, clientSecret, merchantId, merchantName, livemode, money, description, customerEmail, metadata,
                returnUrl, channel, CAPTURE_AUTOMATIC);
    }

    public PaymentIntent(String id, String clientSecret, String merchantId, String merchantName, boolean livemode,
                         Money money, String description, String customerEmail, Map<String, String> metadata,
                         String returnUrl, String channel, String captureMethod) {
        this.id = id;
        this.channel = channel;
        this.clientSecret = clientSecret;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.livemode = livemode;
        this.amount = money.minor();
        this.currency = money.currency().getCurrencyCode();
        this.status = PaymentStatus.REQUIRES_PAYMENT_METHOD;
        this.captureMethod = captureMethod;
        this.description = description;
        this.customerEmail = customerEmail;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.returnUrl = returnUrl;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // --- state machine -------------------------------------------------------------------------

    private void moveTo(PaymentStatus next) {
        status.requireCanMoveTo(next);
        status = next;
        updatedAt = Instant.now();
    }

    public void startProcessing(String email) {
        moveTo(PaymentStatus.PROCESSING);
        if (email != null && !email.isBlank()) {
            customerEmail = email.trim();
        }
    }

    public void requireAction() {
        moveTo(PaymentStatus.REQUIRES_ACTION);
    }

    /** After a successful 3DS challenge the payment goes back to processing before it settles. */
    public void resumeProcessing() {
        moveTo(PaymentStatus.PROCESSING);
    }

    public void succeed() {
        moveTo(PaymentStatus.SUCCEEDED);
        failureCode = null;
        failureMessage = null;
    }

    /**
     * A buy-now-pay-later provider approved the customer and will pay Cutpay, so the payment is complete. Goes through
     * processing like any payment, so the state table stays the only authority on what can follow what.
     */
    public void payWithBnpl(String provider, String applicationId, long fee) {
        moveTo(PaymentStatus.PROCESSING);
        moveTo(PaymentStatus.SUCCEEDED);
        paymentMethodType = "bnpl";
        bnplProvider = provider;
        bnplApplicationId = applicationId;
        bnplFee = fee;
        failureCode = null;
        failureMessage = null;
    }

    /** How the customer is paying: {@code card}, {@code apple_pay}, {@code google_pay} or {@code blik}. */
    public void useMethod(String type) {
        paymentMethodType = type;
    }

    public String getPaymentMethodType() {
        return paymentMethodType;
    }

    public String getBnplProvider() {
        return bnplProvider;
    }

    public String getBnplApplicationId() {
        return bnplApplicationId;
    }

    public Long getBnplFee() {
        return bnplFee;
    }

    public void fail(String code, String message) {
        moveTo(PaymentStatus.FAILED);
        failureCode = code;
        failureMessage = message;
    }

    public void cancel() {
        moveTo(PaymentStatus.CANCELED);
    }

    public boolean isManualCapture() {
        return CAPTURE_MANUAL.equals(captureMethod);
    }

    /** The card issuer approved the amount and it is now held; it must be captured before {@code until}. */
    public void authorize(Instant until) {
        moveTo(PaymentStatus.REQUIRES_CAPTURE);
        amountAuthorized = amount;
        captureBefore = until;
        failureCode = null;
        failureMessage = null;
    }

    /**
     * Takes {@code captured} of the held amount. The rest is released, so from now on the payment is for the
     * captured amount only: that is what refunds, fees and the ledger see.
     */
    public void capture(long captured) {
        if (captured < 1 || captured > amount) {
            throw new IllegalArgumentException("Capture must be between 1 and " + amount);
        }
        moveTo(PaymentStatus.SUCCEEDED);
        amount = captured;
        captureBefore = null;
    }

    /** The merchant never captured in time, so the hold is given back. */
    public void expire() {
        moveTo(PaymentStatus.CANCELED);
        captureBefore = null;
        failureCode = "authorization_expired";
        failureMessage = "The authorisation was not captured in time and has been released.";
    }

    // --- refunds -------------------------------------------------------------------------------

    public long refundable() {
        return amount - amountRefunded;
    }

    /** Sets money aside for a refund that is being processed so a concurrent one cannot take it too. */
    public void reserveRefund(long refundAmount) {
        amountRefunded = Math.addExact(amountRefunded, refundAmount);
        updatedAt = Instant.now();
    }

    public void releaseRefund(long refundAmount) {
        amountRefunded -= refundAmount;
        updatedAt = Instant.now();
    }

    /** Recomputes the status from the money that has really been returned. */
    public void applyRefundedTotal(long succeededTotal) {
        PaymentStatus next = succeededTotal >= amount ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        if (status != next) {
            moveTo(next);
        }
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public boolean isLivemode() {
        return livemode;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getCaptureMethod() {
        return captureMethod;
    }

    public String getDescription() {
        return description;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public String getChannel() {
        return channel;
    }

    /** Ties the payment to a customer, optionally charged to one of their saved cards. */
    public void forCustomer(String customerId, String paymentMethodId, boolean offSession, String setupFutureUsage) {
        this.customerId = customerId;
        this.paymentMethodId = paymentMethodId;
        this.offSession = offSession;
        this.setupFutureUsage = setupFutureUsage;
    }

    /** The saved card now tied to the payment, e.g. after it was saved at checkout. */
    public void usePaymentMethod(String paymentMethodId) {
        this.paymentMethodId = paymentMethodId;
    }

    public void setApplicationFeeAmount(Long applicationFeeAmount) {
        this.applicationFeeAmount = applicationFeeAmount;
    }

    public Long getApplicationFeeAmount() {
        return applicationFeeAmount;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getPaymentMethodId() {
        return paymentMethodId;
    }

    public boolean isOffSession() {
        return offSession;
    }

    public String getSetupFutureUsage() {
        return setupFutureUsage;
    }

    public Long getAmountAuthorized() {
        return amountAuthorized;
    }

    public Instant getCaptureBefore() {
        return captureBefore;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getReturnUrl() {
        return returnUrl;
    }

    public long getAmountRefunded() {
        return amountRefunded;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
