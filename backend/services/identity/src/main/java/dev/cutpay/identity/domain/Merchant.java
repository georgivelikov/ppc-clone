package dev.cutpay.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "merchants")
public class Merchant {

    public enum Status { PENDING_KYC, ACTIVE, SUSPENDED }

    @Id
    private String id;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Merchant() {
    }

    public Merchant(String id, String legalName, String country, String defaultCurrency) {
        this.id = id;
        this.legalName = legalName;
        this.displayName = legalName;
        this.country = country;
        this.defaultCurrency = defaultCurrency;
        this.status = Status.PENDING_KYC;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCountry() {
        return country;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
