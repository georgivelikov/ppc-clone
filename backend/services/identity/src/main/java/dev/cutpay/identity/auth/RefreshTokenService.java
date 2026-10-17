package dev.cutpay.identity.auth;

import dev.cutpay.identity.domain.RefreshToken;
import dev.cutpay.identity.domain.Repositories.RefreshTokenRepository;
import dev.cutpay.identity.security.AuthProperties;
import dev.cutpay.web.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Opaque, single-use refresh tokens with rotation and reuse detection (docs/adr/0003-authentication.md).
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final AuthProperties properties;

    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** Creates a token and returns the raw value, which is never stored. */
    public String create(String userId, String merchantId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(Ids.generate("rt"), userId, merchantId, hash(raw),
                Instant.now().plus(properties.refreshTokenTtl())));
        return raw;
    }

    /**
     * Validates and consumes a token. A token that was already used is evidence of theft (either the
     * thief or the owner is replaying it), so every session of that user is revoked.
     */
    public Optional<RefreshToken> consume(String raw) {
        Optional<RefreshToken> found = repository.findByTokenHash(hash(raw));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken token = found.get();
        if (token.isRevoked()) {
            repository.revokeAllForUser(token.getUserId());
            return Optional.empty();
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        token.revoke();
        return Optional.of(token);
    }

    /** Revokes a token if it exists (used by logout). */
    public void revoke(String raw) {
        repository.findByTokenHash(hash(raw)).ifPresent(RefreshToken::revoke);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
