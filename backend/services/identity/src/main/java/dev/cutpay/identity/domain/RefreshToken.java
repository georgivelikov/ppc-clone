package dev.cutpay.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Only the SHA-256 hash of the token is stored; the raw value lives in the user's cookie. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "family_id", nullable = false)
    private String familyId;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefreshToken() {
    }

    public RefreshToken(String id, String userId, String merchantId, String tokenHash, Instant expiresAt,
                        String familyId, String userAgent) {
        this.id = id;
        this.familyId = familyId == null ? id : familyId;
        this.userAgent = userAgent;
        this.userId = userId;
        this.merchantId = merchantId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getFamilyId() {
        return familyId;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getUserId() {
        return userId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRotated() {
        return rotatedAt != null;
    }

    /** Spent by a refresh. */
    public void rotate() {
        revoke();
        if (rotatedAt == null) {
            rotatedAt = Instant.now();
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }
}
