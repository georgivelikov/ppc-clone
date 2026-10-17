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
        this.id = id;
        this.clientSecret = clientSecret;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.livemode = livemode;
        this.amount = money.minor();
        this.currency = money.currency().getCurrencyCode();
        this.status = PaymentStatus.REQUIRES_PAYMENT_METHOD;
        this.captureMethod = "automatic";
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

    public void fail(String code, String message) {
        moveTo(PaymentStatus.FAILED);
        failureCode = code;
        failureMessage = message;
    }

    public void cancel() {
        moveTo(PaymentStatus.CANCELED);
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
