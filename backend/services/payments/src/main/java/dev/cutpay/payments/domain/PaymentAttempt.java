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

    @Column(name = "card_type")
    private String cardType;

    @Column(name = "card_region")
    private String cardRegion;

    @Column(name = "acquirer_token")
    private String acquirerToken;

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
                          String fingerprint, String cardType, String cardRegion) {
        this.id = id;
        this.paymentIntentId = paymentIntentId;
        this.methodType = "card";
        this.cardBrand = brand;
        this.cardLast4 = last4;
        this.cardExpMonth = expMonth;
        this.cardExpYear = expYear;
        this.cardFingerprint = fingerprint;
        this.cardType = cardType;
        this.cardRegion = cardRegion;
        this.status = "processing";
        this.createdAt = Instant.now();
    }

    /** The attempt that a buy-now-pay-later approval stands for: no card, the application is the reference. */
    public static PaymentAttempt bnpl(String id, String paymentIntentId, String applicationId) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = id;
        attempt.paymentIntentId = paymentIntentId;
        attempt.methodType = "bnpl";
        attempt.acquirerRef = applicationId;
        attempt.status = "succeeded";
        attempt.createdAt = Instant.now();
        return attempt;
    }

    /** A BLIK payment: no card, the customer confirms a request in their banking app. */
    public static PaymentAttempt blik(String id, String paymentIntentId) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = id;
        attempt.paymentIntentId = paymentIntentId;
        attempt.methodType = "blik";
        attempt.status = "processing";
        attempt.createdAt = Instant.now();
        return attempt;
    }

    /** The card behind this attempt was presented by a wallet ({@code apple_pay}, {@code google_pay}). */
    public PaymentAttempt viaWallet(String walletType) {
        this.methodType = walletType;
        return this;
    }

    public boolean isCard() {
        return "card".equals(methodType);
    }

    public void requiresAction(String acquirerRef) {
        this.acquirerRef = acquirerRef;
        this.status = "requires_action";
        this.threeDsStatus = "challenge_required";
    }

    /** A BLIK request is waiting in the customer's banking app. */
    public void awaitingBank(String acquirerRef) {
        this.acquirerRef = acquirerRef;
        this.status = "requires_action";
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

    /** The issuer approved and the amount is held, but nothing has been taken yet. */
    public void authorized(String acquirerRef) {
        if (acquirerRef != null) {
            this.acquirerRef = acquirerRef;
        }
        this.status = "authorized";
        if (threeDsStatus != null) {
            this.threeDsStatus = "authenticated";
        }
    }

    /** The held amount was captured (or released: {@code released}). */
    public void captured() {
        this.status = "succeeded";
    }

    public void released() {
        this.status = "canceled";
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

    public String getCardType() {
        return cardType;
    }

    public String getCardRegion() {
        return cardRegion;
    }

    public PaymentAttempt withToken(String token) {
        this.acquirerToken = token;
        return this;
    }

    public String getAcquirerToken() {
        return acquirerToken;
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
