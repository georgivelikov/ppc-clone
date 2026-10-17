package dev.cutpay.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @Test
    void addsAndSubtractsInMinorUnits() {
        Money a = Money.ofMinor(1010, "EUR");
        Money b = Money.ofMinor(20, "EUR");

        assertThat(a.plus(b)).isEqualTo(Money.ofMinor(1030, "EUR"));
        assertThat(a.minus(b)).isEqualTo(Money.ofMinor(990, "EUR"));
        assertThat(b.minus(a).isNegative()).isTrue();
    }

    @Test
    void decimalArithmeticIsExact() {
        // The classic floating point trap: 0.1 + 0.2
        Money sum = Money.of(new BigDecimal("0.10"), "EUR").plus(Money.of(new BigDecimal("0.20"), "EUR"));
        assertThat(sum).isEqualTo(Money.of(new BigDecimal("0.30"), "EUR"));
    }

    @ParameterizedTest
    @CsvSource({
            "12.34, EUR, 1234",
            "12,    EUR, 1200",
            "1500,  HUF, 150000", // ISO 4217 gives HUF two decimals
            "99.99, PLN, 9999",
    })
    void parsesMajorUnits(String major, String currency, long expectedMinor) {
        assertThat(Money.of(new BigDecimal(major), currency).minor()).isEqualTo(expectedMinor);
    }

    @Test
    void rejectsTooManyDecimals() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.234"), "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more decimals");
    }

    @Test
    void rejectsMixingCurrencies() {
        assertThatThrownBy(() -> Money.ofMinor(1, "EUR").plus(Money.ofMinor(1, "RON")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @ParameterizedTest
    @CsvSource({
            // amount, rate,  expected fee (HALF_EVEN: ties go to the even neighbour)
            "10000,  0.0129, 129",
            "50,     0.0129, 1",   // 0.645 -> 1
            "250,    0.01,   2",   // 2.5   -> 2 (even)
            "350,    0.01,   4",   // 3.5   -> 4 (even)
    })
    void multipliesWithBankersRounding(long amount, String rate, long expected) {
        Money fee = Money.ofMinor(amount, "EUR").multiply(new BigDecimal(rate), RoundingMode.HALF_EVEN);
        assertThat(fee.minor()).isEqualTo(expected);
    }

    @Test
    void detectsOverflow() {
        assertThatThrownBy(() -> Money.ofMinor(Long.MAX_VALUE, "EUR").plus(Money.ofMinor(1, "EUR")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void formatsForHumans() {
        assertThat(Money.ofMinor(123456, "EUR")).hasToString("1234.56 EUR");
    }

    @Test
    void knowsCountryDefaults() {
        assertThat(SupportedCurrencies.defaultFor("bg")).isEqualTo("EUR");
        assertThat(SupportedCurrencies.defaultFor("RO")).isEqualTo("RON");
        assertThat(SupportedCurrencies.isSupported("pln")).isTrue();
        assertThat(SupportedCurrencies.isSupported("JPY")).isFalse();
    }
}
