package dev.cutpay.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FieldCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String IBAN = "BG80BNBG96611020345678";

    private final LocalFieldCipher local = new LocalFieldCipher(KEY);

    @Test
    void aValueComesBackAndIsNotStoredAsItIs() {
        String stored = local.encrypt(IBAN, "mrc_1");
        assertThat(stored).startsWith("local:v1:").doesNotContain(IBAN);
        assertThat(local.decrypt(stored, "mrc_1")).isEqualTo(IBAN);
    }

    @Test
    void theSameValueEncryptsDifferentlyEachTime() {
        assertThat(local.encrypt(IBAN, "mrc_1")).isNotEqualTo(local.encrypt(IBAN, "mrc_1"));
    }

    @Test
    void aCiphertextMovedToAnotherRowCannotBeRead() {
        String stored = local.encrypt(IBAN, "mrc_1");
        assertThatThrownBy(() -> local.decrypt(stored, "mrc_2")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void legacyPlainTextPassesThrough() {
        assertThat(local.isEncrypted(IBAN)).isFalse();
        assertThat(local.decrypt(IBAN, "mrc_1")).isEqualTo(IBAN);
    }

    @Test
    void aTamperedValueIsRejected() {
        String stored = local.encrypt(IBAN, "mrc_1");
        char last = stored.charAt(stored.length() - 3);
        String tampered = stored.substring(0, stored.length() - 3) + (last == 'A' ? 'B' : 'A') + stored.substring(stored.length() - 2);
        assertThatThrownBy(() -> local.decrypt(tampered, "mrc_1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void vaultTransitIsCalledWithTheTokenTheContextAndNoPlainTextInTheStoredValue() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://vault.test").defaultHeader("X-Vault-Token", "t0ken");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var cipher = new VaultTransitCipher(builder.build(), "transit", "cutpay-pii");

        server.expect(requestTo("http://vault.test/v1/transit/encrypt/cutpay-pii"))
                .andExpect(header("X-Vault-Token", "t0ken"))
                .andExpect(jsonPath("$.plaintext").value(Base64.getEncoder().encodeToString(IBAN.getBytes())))
                .andExpect(jsonPath("$.context").value(Base64.getEncoder().encodeToString("mrc_1".getBytes())))
                .andRespond(withSuccess("{\"data\":{\"ciphertext\":\"vault:v1:abc\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://vault.test/v1/transit/decrypt/cutpay-pii"))
                .andRespond(withSuccess("{\"data\":{\"plaintext\":\"" + Base64.getEncoder().encodeToString(IBAN.getBytes()) + "\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(cipher.encrypt(IBAN, "mrc_1")).isEqualTo("vault:v1:abc");
        assertThat(cipher.decrypt("vault:v1:abc", "mrc_1")).isEqualTo(IBAN);
        assertThat(cipher.decrypt(IBAN, "mrc_1")).isEqualTo(IBAN); // legacy plain text: no call to Vault
        server.verify();
    }
}
