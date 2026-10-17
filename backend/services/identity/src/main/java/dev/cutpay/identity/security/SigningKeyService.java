package dev.cutpay.identity.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the RSA key pair used to sign access tokens. The pair is generated on first start and stored in
 * the {@code signing_keys} table so tokens survive restarts (see docs/adr/0003-authentication.md).
 */
@Service
public class SigningKeyService {

    private final RSAKey rsaKey;

    public SigningKeyService(JdbcClient jdbc, TransactionTemplate tx) {
        this.rsaKey = loadOrCreate(jdbc, tx);
    }

    public RSAKey signingKey() {
        return rsaKey;
    }

    public RSAPublicKey publicKey() {
        try {
            return rsaKey.toRSAPublicKey();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext> jwkSource() {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /** The JWKS document with public keys only. */
    public String publicJwksJson() {
        return new JWKSet(rsaKey.toPublicJWK()).toString();
    }

    private static RSAKey loadOrCreate(JdbcClient jdbc, TransactionTemplate tx) {
        var existing = jdbc.sql("SELECT kid, private_key, public_key FROM signing_keys WHERE active")
                .query((rs, n) -> toRsaKey(rs.getString("kid"), rs.getString("private_key"), rs.getString("public_key")))
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        String kid = "key-" + UUID.randomUUID();
        KeyPair pair = generate();
        tx.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO signing_keys (kid, private_key, public_key, active)
                        VALUES (:kid, :priv, :pub, true)
                        ON CONFLICT DO NOTHING
                        """)
                .param("kid", kid)
                .param("priv", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()))
                .param("pub", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                .update());
        // Another instance may have won the race for the single active key, so always re-read.
        return jdbc.sql("SELECT kid, private_key, public_key FROM signing_keys WHERE active")
                .query((rs, n) -> toRsaKey(rs.getString("kid"), rs.getString("private_key"), rs.getString("public_key")))
                .single();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey toRsaKey(String kid, String privateB64, String publicB64) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateB64)));
            var publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicB64)));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(kid)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Stored signing key is unreadable", e);
        }
    }
}
