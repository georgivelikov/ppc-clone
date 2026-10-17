package dev.cutpay.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "api_keys")
public class ApiKey {

    public enum Type {
        SECRET("sk"), PUBLISHABLE("pk"), RESTRICTED("rk");

        private final String prefix;

        Type(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean livemode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private String prefix;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Column(nullable = false)
    private String last4;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> scopes;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected ApiKey() {
    }

    public ApiKey(String id, String merchantId, String name, boolean livemode, Type type, String prefix,
                  String keyHash, String last4, List<String> scopes, String createdBy) {
        this.id = id;
        this.merchantId = merchantId;
        this.name = name;
        this.livemode = livemode;
        this.type = type;
        this.prefix = prefix;
        this.keyHash = keyHash;
        this.last4 = last4;
        this.scopes = scopes;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }

    /** Lets the key live on for a grace period, or ends it now when the period is zero. */
    public void expireAt(Instant at) {
        if (expiresAt == null || at.isBefore(expiresAt)) {
            expiresAt = at;
        }
    }

    public void touch(Instant now) {
        lastUsedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getName() {
        return name;
    }

    public boolean isLivemode() {
        return livemode;
    }

    public Type getType() {
        return type;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getLast4() {
        return last4;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
