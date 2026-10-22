package dev.cutpay.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Encryption by HashiCorp Vault's transit engine. The application never holds a key: it sends the value to Vault,
 * gets back {@code vault:v1:...} and stores that. Anyone who copies the database gets ciphertext that is useless
 * without Vault, and Vault can rotate the key (new writes use the new version, old ciphertext still decrypts and
 * can be rewrapped) and cut access off by revoking the token.
 *
 * <p>The key is "derived": the context (the merchant, the account) becomes part of the key, so a ciphertext cannot be
 * moved to another row and read there.
 */
public class VaultTransitCipher implements FieldCipher {

    static final String PREFIX = "vault:";

    private final RestClient vault;
    private final String keyName;
    private final String mount;

    public VaultTransitCipher(RestClient vault, String mount, String keyName) {
        this.vault = vault;
        this.mount = mount;
        this.keyName = keyName;
    }

    /**
     * Makes sure the transit engine and the key exist. For development, where Vault is started empty; in a real
     * deployment this is infrastructure's job and the token has no right to do it.
     */
    public void bootstrap() {
        try {
            vault.post().uri("/v1/sys/mounts/{mount}", mount).body(Map.of("type", "transit")).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (!e.getResponseBodyAsString().contains("already in use")) {
                throw e;
            }
        }
        vault.post().uri("/v1/{mount}/keys/{key}", mount, keyName).body(Map.of("derived", true)).retrieve().toBodilessEntity();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String encrypt(String plaintext, String context) {
        Map<String, Object> response = vault.post().uri("/v1/{mount}/encrypt/{key}", mount, keyName)
                .body(Map.of("plaintext", b64(plaintext), "context", b64(context)))
                .retrieve().body(Map.class);
        return (String) ((Map<String, Object>) response.get("data")).get("ciphertext");
    }

    @Override
    @SuppressWarnings("unchecked")
    public String decrypt(String stored, String context) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        Map<String, Object> response = vault.post().uri("/v1/{mount}/decrypt/{key}", mount, keyName)
                .body(Map.of("ciphertext", stored, "context", b64(context)))
                .retrieve().body(Map.class);
        String plaintext = (String) ((Map<String, Object>) response.get("data")).get("plaintext");
        return new String(Base64.getDecoder().decode(plaintext), StandardCharsets.UTF_8);
    }

    @Override
    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
