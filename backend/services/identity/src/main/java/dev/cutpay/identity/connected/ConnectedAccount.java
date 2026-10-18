package dev.cutpay.identity.connected;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A sub-merchant of a platform. The full IBAN stays here and in payouts; responses only show a masked one. */
@Entity
@Table(name = "connected_accounts")
public class ConnectedAccount {

    public static final String ACTIVE = "active";
    public static final String DISABLED = "disabled";

    @Id
    private String id;

    @Column(name = "platform_merchant_id", nullable = false, updatable = false)
    private String platformMerchantId;

    @Column(nullable = false, updatable = false)
    private boolean livemode;

    @Column(nullable = false)
    private String name;

    private String email;

    @Column(nullable = false)
    private String iban;

    @Column(nullable = false, updatable = false, length = 2)
    private String country;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConnectedAccount() {
    }

    public ConnectedAccount(String id, String platformMerchantId, boolean livemode, String name, String email,
                            String iban, String country, String currency, Instant now) {
        this.id = id;
        this.platformMerchantId = platformMerchantId;
        this.livemode = livemode;
        this.name = name;
        this.email = email;
        this.iban = iban;
        this.country = country;
        this.currency = currency;
        this.status = ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String email, String iban, Instant now) {
        if (name != null) {
            this.name = name;
        }
        if (email != null) {
            this.email = email;
        }
        if (iban != null) {
            this.iban = iban;
        }
        this.updatedAt = now;
    }

    public void disable(Instant now) {
        this.status = DISABLED;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public String getId() {
        return id;
    }

    public String getPlatformMerchantId() {
        return platformMerchantId;
    }

    public boolean isLivemode() {
        return livemode;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getIban() {
        return iban;
    }

    public String getCountry() {
        return country;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
