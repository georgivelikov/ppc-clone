package dev.cutpay.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One try to pay an intent. Holds only what is safe to keep: brand, last four digits, expiry, fingerprint. */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

    @Id
    private String id;

    @Column(name = "payment_intent_id", nullable = false)
    private String paymentIntentId;

    @Column(name = "method_type", nullable = false)
    private String methodType;

    @Column(name = "card_brand")
    private String cardBrand;

    @Column(name = "card_last4")
    private String cardLast4;

    @Column(name = "card_exp_month")
    private Integer cardExpMonth;

    @Column(name = "card_exp_year")
    private Integer cardExpYear;

    @Column(name = "card_fingerprint")
    private String cardFingerprint;

    @Column(name = "acquirer_ref")
    private String acquirerRef;

    @Column(nullable = false)
    private String status;

    @Column(name = "decline_code")
    private String declineCode;

    @Column(name = "three_ds_status")
    private String threeDsStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(String id, String paymentIntentId, String brand, String last4, int expMonth, int expYear,
                          String fingerprint) {
        this.id = id;
        this.paymentIntentId = paymentIntentId;
        this.methodType = "card";
        this.cardBrand = brand;
        this.cardLast4 = last4;
        this.cardExpMonth = expMonth;
        this.cardExpYear = expYear;
        this.cardFingerprint = fingerprint;
        this.status = "processing";
        this.createdAt = Instant.now();
    }

    public void requiresAction(String acquirerRef) {
        this.acquirerRef = acquirerRef;
        this.status = "requires_action";
        this.threeDsStatus = "challenge_required";
    }

    public void succeeded(String acquirerRef) {
        if (acquirerRef != null) {
            this.acquirerRef = acquirerRef;
        }
        this.status = "succeeded";
        if (threeDsStatus != null) {
            this.threeDsStatus = "authenticated";
        }
    }

    public void failed(String declineCode) {
        this.status = "failed";
        this.declineCode = declineCode;
        if (threeDsStatus != null) {
            this.threeDsStatus = "failed";
        }
    }

    public String getId() {
        return id;
    }

    public String getPaymentIntentId() {
        return paymentIntentId;
    }

    public String getMethodType() {
        return methodType;
    }

    public String getCardBrand() {
        return cardBrand;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public Integer getCardExpMonth() {
        return cardExpMonth;
    }

    public Integer getCardExpYear() {
        return cardExpYear;
    }

    public String getCardFingerprint() {
        return cardFingerprint;
    }

    public String getAcquirerRef() {
        return acquirerRef;
    }

    public String getStatus() {
        return status;
    }

    public String getDeclineCode() {
        return declineCode;
    }

    public String getThreeDsStatus() {
        return threeDsStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
