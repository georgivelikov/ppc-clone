package dev.cutpay.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A card saved for a customer. Holds what is safe to show plus the vault token; never the number. */
@Entity
@Table(name = "payment_methods")
public class PaymentMethod {

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(nullable = false, updatable = false)
    private boolean livemode;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(nullable = false)
    private String type;

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

    @Column(name = "acquirer_token", nullable = false, updatable = false)
    private String acquirerToken;

    @Column(name = "detached_at")
    private Instant detachedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentMethod() {
    }

    public PaymentMethod(String id, String merchantId, boolean livemode, String customerId, String brand, String last4,
                         int expMonth, int expYear, String fingerprint, String cardType, String cardRegion,
                         String acquirerToken, Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.livemode = livemode;
        this.customerId = customerId;
        this.type = "card";
        this.cardBrand = brand;
        this.cardLast4 = last4;
        this.cardExpMonth = expMonth;
        this.cardExpYear = expYear;
        this.cardFingerprint = fingerprint;
        this.cardType = cardType;
        this.cardRegion = cardRegion;
        this.acquirerToken = acquirerToken;
        this.createdAt = now;
    }

    public void detach(Instant now) {
        this.detachedAt = now;
    }

    public boolean isDetached() {
        return detachedAt != null;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public boolean isLivemode() {
        return livemode;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getType() {
        return type;
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

    public String getAcquirerToken() {
        return acquirerToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
