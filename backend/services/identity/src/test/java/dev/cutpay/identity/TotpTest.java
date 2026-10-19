package dev.cutpay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cutpay.identity.security.Totp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** The secret "12345678901234567890" of RFC 6238's test vectors, as Base32. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesTheRfc6238TestVectors() {
        // The RFC lists eight digits; the last six of them are what a six-digit code shows.
        assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1111111109))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1234567890))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(2000000000))).isEqualTo("279037");
    }

    @Test
    void acceptsOneStepEitherSideButNoMore() {
        Instant now = Instant.ofEpochSecond(1_700_000_000);
        String current = Totp.code(RFC_SECRET, now);
        assertThat(Totp.verify(RFC_SECRET, current, now)).isTrue();
        assertThat(Totp.verify(RFC_SECRET, current, now.plusSeconds(30))).isTrue();
        assertThat(Totp.verify(RFC_SECRET, current, now.minusSeconds(30))).isTrue();
        assertThat(Totp.verify(RFC_SECRET, current, now.plusSeconds(90))).isFalse();
    }

    @Test
    void refusesMalformedCodes() {
        Instant now = Instant.now();
        assertThat(Totp.verify(RFC_SECRET, null, now)).isFalse();
        assertThat(Totp.verify(RFC_SECRET, "12345", now)).isFalse();
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now)).isFalse();
    }

    @Test
    void newSecretsAreBase32AndDiffer() {
        String a = Totp.newSecret();
        assertThat(a).matches("[A-Z2-7]{32}");
        assertThat(Totp.newSecret()).isNotEqualTo(a);
    }

    @Test
    void theAppAddressCarriesTheSecretAndTheIssuer() {
        assertThat(Totp.uri("Cutpay", "ivan@example.com", RFC_SECRET))
                .startsWith("otpauth://totp/Cutpay:ivan%40example.com?secret=" + RFC_SECRET)
                .contains("issuer=Cutpay");
    }
}
