package dev.cutpay.money;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IbanTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "BG80BNBG96611020345678",
            "DE89370400440532013000",
            "GB82WEST12345698765432",
            // the fake bank's "account closed" IBAN is still a valid IBAN
            "DE94370400440532010000"})
    void acceptsValidIbans(String iban) {
        assertThat(Iban.normalise(iban)).contains(iban);
    }

    @Test
    void ignoresSpacesAndCase() {
        assertThat(Iban.normalise("de89 3704 0044 0532 0130 00")).contains("DE89370400440532013000");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "BG80BNBG96611020345679", // wrong checksum digit
            "BG81BNBG96611020345678", // wrong check digits
            "DE8937040044053201300", // truncated
            "12345678901234567890", // no country
            "BG80BNBG9661102034567!", // illegal character
            ""})
    void rejectsInvalidIbans(String iban) {
        assertThat(Iban.normalise(iban)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(Iban.normalise(null)).isEmpty();
    }

    @Test
    void masksAllButCountryAndLastFour() {
        assertThat(Iban.mask("BG80BNBG96611020345678")).isEqualTo("BG•• •••• 5678");
        assertThat(Iban.last4("BG80BNBG96611020345678")).isEqualTo("5678");
    }
}
