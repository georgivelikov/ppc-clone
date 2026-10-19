package dev.cutpay.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    private String id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_enabled_at")
    private Instant totpEnabledAt;

    protected User() {
    }

    public User(String id, String email, String passwordHash, String fullName) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void markEmailVerified() {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = Instant.now();
        }
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public boolean isTotpEnabled() {
        return totpEnabledAt != null;
    }

    /** Starts setup: the secret is kept but not yet asked for at sign-in. */
    public void beginTotp(String secret) {
        this.totpSecret = secret;
        this.totpEnabledAt = null;
    }

    public void enableTotp() {
        this.totpEnabledAt = Instant.now();
    }

    public void disableTotp() {
        this.totpSecret = null;
        this.totpEnabledAt = null;
    }

    public void changePasswordHash(String hash) {
        this.passwordHash = hash;
    }

    public void recordLogin() {
        this.lastLoginAt = Instant.now();
    }
}
