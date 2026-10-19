package dev.cutpay.identity.security;

import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String ISSUER = "cutpay-identity";

    static final java.time.Duration API_KEY_TOKEN_TTL = java.time.Duration.ofMinutes(5);

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final String keyId;

    public JwtService(JwtEncoder encoder, AuthProperties properties, SigningKeyService keys) {
        this.encoder = encoder;
        this.properties = properties;
        this.keyId = keys.signingKey().getKeyID();
    }

    public IssuedToken issue(String userId, String email, String merchantId, String role) {
        return issue(userId, email, merchantId, role, null);
    }

    /** {@code sessionId} names the sign-in the token belongs to, so the security page can mark the current one. */
    public IssuedToken issue(String userId, String email, String merchantId, String role, String sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("email", email)
                .claim("mid", merchantId)
                .claim("role", role)
                .claim("sid", sessionId)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.accessTokenTtl().toSeconds());
    }

    /**
     * Short-lived token the gateway swaps in for an API key, so downstream services keep verifying one
     * kind of credential. {@code role=api} marks it as a machine caller; {@code livemode} is the key's mode.
     */
    public IssuedToken issueForApiKey(String keyId, String merchantId, boolean livemode, String keyType,
                                      java.util.List<String> scopes) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(API_KEY_TOKEN_TTL);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(keyId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("mid", merchantId)
                .claim("role", "api")
                .claim("livemode", livemode)
                .claim("key_type", keyType)
                .claim("scope", scopes)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(this.keyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, API_KEY_TOKEN_TTL.toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
