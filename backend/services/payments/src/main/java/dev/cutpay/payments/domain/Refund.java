package dev.cutpay.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    private String id;

    @Column(name = "payment_intent_id", nullable = false)
    private String paymentIntentId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    private String reason;

    /** pending, succeeded or failed. */
    @Column(nullable = false)
    private String status;

    @Column(name = "acquirer_ref")
    private String acquirerRef;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Refund() {
    }

    public Refund(String id, String paymentIntentId, String merchantId, long amount, String currency, String reason) {
        this.id = id;
        this.paymentIntentId = paymentIntentId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
        this.status = "pending";
        this.createdAt = Instant.now();
    }

    public void succeeded(String acquirerRef) {
        this.status = "succeeded";
        this.acquirerRef = acquirerRef;
    }

    public void failed(String code) {
        this.status = "failed";
        this.failureCode = code;
    }

    public String getId() {
        return id;
    }

    public String getPaymentIntentId() {
        return paymentIntentId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public String getAcquirerRef() {
        return acquirerRef;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
