package dev.cutpay.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;

/**
 * Saving a customer's card without charging it. The bank may ask the customer to authenticate once (3-D Secure);
 * after that the saved card can be charged off-session.
 */
@Entity
@Table(name = "setup_intents")
public class SetupIntent {

    public enum Status {
        REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION, SUCCEEDED, FAILED, CANCELED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(nullable = false, updatable = false)
    private boolean livemode;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "client_secret", nullable = false, updatable = false)
    private String clientSecret;

    @Column(name = "payment_method_id")
    private String paymentMethodId;

    @Column(name = "acquirer_token")
    private String acquirerToken;

    @Column(name = "acquirer_ref")
    private String acquirerRef;

    private String description;

    @Column(name = "return_url")
    private String returnUrl;

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

    protected SetupIntent() {
    }

    public SetupIntent(String id, String clientSecret, String merchantId, String merchantName, boolean livemode,
                       String customerId, String description, String returnUrl, Instant now) {
        this.id = id;
        this.clientSecret = clientSecret;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.livemode = livemode;
        this.customerId = customerId;
        this.description = description;
        this.returnUrl = returnUrl;
        this.status = Status.REQUIRES_PAYMENT_METHOD;
        this.createdAt = now;
        this.updatedAt = now;
    }

    private void moveTo(Status next, Instant now) {
        boolean allowed = switch (status) {
            case REQUIRES_PAYMENT_METHOD -> next != Status.REQUIRES_PAYMENT_METHOD;
            case REQUIRES_ACTION -> next == Status.SUCCEEDED || next == Status.FAILED || next == Status.CANCELED;
            default -> false;
        };
        if (!allowed) {
            throw new IllegalTransitionException(status.name(), next.name());
        }
        status = next;
        updatedAt = now;
    }

    public void requireAction(String token, String acquirerRef, Instant now) {
        this.acquirerToken = token;
        this.acquirerRef = acquirerRef;
        moveTo(Status.REQUIRES_ACTION, now);
    }

    public void succeed(String paymentMethodId, Instant now) {
        moveTo(Status.SUCCEEDED, now);
        this.paymentMethodId = paymentMethodId;
        this.failureCode = null;
        this.failureMessage = null;
    }

    public void fail(String code, String message, Instant now) {
        moveTo(Status.FAILED, now);
        this.failureCode = code;
        this.failureMessage = message;
    }

    public void cancel(Instant now) {
        moveTo(Status.CANCELED, now);
    }

    /** Remembers the card and bank reference while a confirmation is in flight. */
    public void attempt(String token, String acquirerRef) {
        this.acquirerToken = token;
        this.acquirerRef = acquirerRef;
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

    public String getCustomerId() {
        return customerId;
    }

    public Status getStatus() {
        return status;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getPaymentMethodId() {
        return paymentMethodId;
    }

    public String getAcquirerToken() {
        return acquirerToken;
    }

    public String getAcquirerRef() {
        return acquirerRef;
    }

    public String getDescription() {
        return description;
    }

    public String getReturnUrl() {
        return returnUrl;
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
