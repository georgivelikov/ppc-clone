package dev.cutpay.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A person or company a merchant sells to repeatedly. Deleting one only hides it: its payments stay on record. */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(nullable = false, updatable = false)
    private boolean livemode;

    private String email;
    private String name;
    private String phone;
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> address;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> metadata;

    @Column(name = "default_payment_method")
    private String defaultPaymentMethod;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {
    }

    public Customer(String id, String merchantId, boolean livemode, String email, String name, String phone,
                    String description, Map<String, String> address, Map<String, String> metadata, Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.livemode = livemode;
        this.email = email;
        this.name = name;
        this.phone = phone;
        this.description = description;
        this.address = address;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Only the fields that were sent are changed. */
    public void update(String email, String name, String phone, String description, Map<String, String> address,
                       Map<String, String> metadata, Instant now) {
        if (email != null) {
            this.email = email;
        }
        if (name != null) {
            this.name = name;
        }
        if (phone != null) {
            this.phone = phone;
        }
        if (description != null) {
            this.description = description;
        }
        if (address != null) {
            this.address = address;
        }
        if (metadata != null) {
            this.metadata = Map.copyOf(metadata);
        }
        this.updatedAt = now;
    }

    public void setDefaultPaymentMethod(String paymentMethodId, Instant now) {
        this.defaultPaymentMethod = paymentMethodId;
        this.updatedAt = now;
    }

    public void delete(Instant now) {
        this.deletedAt = now;
        this.defaultPaymentMethod = null;
        this.updatedAt = now;
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

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, String> getAddress() {
        return address;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public String getDefaultPaymentMethod() {
        return defaultPaymentMethod;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
