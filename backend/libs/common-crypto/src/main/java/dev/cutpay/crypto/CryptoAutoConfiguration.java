package dev.cutpay.crypto;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * Picks the cipher from configuration. There is no silent default: a service that asks for a {@link FieldCipher}
 * must say where its keys come from, because storing personal data in plain text by forgetting a setting is the
 * failure this exists to prevent.
 *
 * <ul>
 *   <li>{@code cutpay.crypto.provider=vault}: Vault transit ({@code cutpay.crypto.vault.address}, {@code .token},
 *       {@code .key}, {@code .mount}, {@code .bootstrap}).
 *   <li>{@code cutpay.crypto.provider=local}: AES-GCM with {@code cutpay.crypto.local-key} (base64, 32 bytes).
 * </ul>
 */
@AutoConfiguration
public class CryptoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FieldCipher.class)
    @ConditionalOnProperty(name = "cutpay.crypto.provider", havingValue = "vault")
    FieldCipher vaultCipher(Environment env) {
        RestClient client = RestClient.builder()
                .baseUrl(env.getRequiredProperty("cutpay.crypto.vault.address"))
                .defaultHeader("X-Vault-Token", env.getRequiredProperty("cutpay.crypto.vault.token"))
                .build();
        var cipher = new VaultTransitCipher(client, env.getProperty("cutpay.crypto.vault.mount", "transit"),
                env.getProperty("cutpay.crypto.vault.key", "cutpay-pii"));
        if (env.getProperty("cutpay.crypto.vault.bootstrap", Boolean.class, false)) {
            cipher.bootstrap();
        }
        return cipher;
    }

    @Bean
    @ConditionalOnMissingBean(FieldCipher.class)
    @ConditionalOnProperty(name = "cutpay.crypto.provider", havingValue = "local")
    FieldCipher localCipher(Environment env) {
        return new LocalFieldCipher(env.getRequiredProperty("cutpay.crypto.local-key"));
    }
}
