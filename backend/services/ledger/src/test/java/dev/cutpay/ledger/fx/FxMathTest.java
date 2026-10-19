package dev.cutpay.ledger.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FxMathTest {

    private static final List<String> CURRENCIES = List.of("EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF");

    @Test
    void aRateBetweenTwoCurrenciesIsWorkedOutFromWhatOneEuroBuys() {
        BigDecimal ronToEur = FxMath.cross(new BigDecimal("4.97"), BigDecimal.ONE);
        assertThat(ronToEur).isEqualByComparingTo("0.201207243461");
        BigDecimal ronToPln = FxMath.cross(new BigDecimal("4.97"), new BigDecimal("4.28"));
        assertThat(ronToPln).isEqualByComparingTo("0.861167002012");
    }

    @Test
    void theMerchantGetsTheMarketValueLessTheMarkupAndTheThreeAddUp() {
        // 100.00 EUR to PLN at 4.28 with a 1% markup.
        var c = FxMath.convert(10_000, "EUR", "PLN", new BigDecimal("4.28"), 100);
        assertThat(c.mid()).isEqualTo(42_800);
        assertThat(c.merchant()).isEqualTo(42_372);
        assertThat(c.markup()).isEqualTo(428);
    }

    @Test
    void ronFiveHundredToEuros() {
        var c = FxMath.convert(50_000, "RON", "EUR", FxMath.cross(new BigDecimal("4.97"), BigDecimal.ONE), 100);
        assertThat(c.mid()).isEqualTo(10_060); // 100.60 EUR
        assertThat(c.merchant()).isEqualTo(9_959); // 99.59 EUR, rounded down
        assertThat(c.markup()).isEqualTo(101);
        assertThat(c.merchant() + c.markup()).isEqualTo(c.mid());
    }

    @Test
    void theMerchantIsNeverPaidMoreThanTheMarketValueWhateverTheRounding() {
        BigDecimal[] perEuro = {new BigDecimal("1"), new BigDecimal("4.97"), new BigDecimal("4.28"), new BigDecimal("395"),
                new BigDecimal("24.4"), new BigDecimal("0.86"), new BigDecimal("1.08"), new BigDecimal("0.95")};
        for (int from = 0; from < CURRENCIES.size(); from++) {
            for (int to = 0; to < CURRENCIES.size(); to++) {
                if (from == to) {
                    continue;
                }
                BigDecimal rate = FxMath.cross(perEuro[from], perEuro[to]);
                for (long amount : new long[] {1, 7, 99, 1_001, 12_345, 987_654_321L}) {
                    for (int bps : new int[] {0, 1, 100, 250, 1_000}) {
                        var c = FxMath.convert(amount, CURRENCIES.get(from), CURRENCIES.get(to), rate, bps);
                        String label = "%d %s->%s at %d bps".formatted(amount, CURRENCIES.get(from), CURRENCIES.get(to), bps);
                        assertThat(c.markup()).as(label).isGreaterThanOrEqualTo(0);
                        assertThat(c.merchant() + c.markup()).as(label).isEqualTo(c.mid());
                        if (bps == 0) {
                            assertThat(c.markup()).as(label).isLessThanOrEqualTo(1); // only the rounding of the market value
                        }
                    }
                }
            }
        }
    }

    @Test
    void everyCurrencyOfThePlatformHasTwoDecimalsEvenTheForint() {
        for (String currency : CURRENCIES) {
            assertThat(FxMath.exponent(currency)).as(currency).isEqualTo(2);
        }
    }

    @Test
    void currenciesWithADifferentNumberOfDecimalsAreScaledBetweenThem() {
        // 1000 yen has no minor unit; 0.0062 EUR per yen is 6.20 EUR, which is 620 cents.
        var c = FxMath.convert(1_000, "JPY", "EUR", new BigDecimal("0.0062"), 0);
        assertThat(c.mid()).isEqualTo(620);
        // And the other way: 10.00 EUR at 161.29 yen per euro is 1613 yen (whole numbers).
        var back = FxMath.convert(1_000, "EUR", "JPY", new BigDecimal("161.29"), 0);
        assertThat(back.mid()).isEqualTo(1_613);
    }

    @Test
    void smallAmountsCanComeOutAsNothing() {
        // 1 HUF fillér is worth a fraction of a euro cent.
        var c = FxMath.convert(1, "HUF", "EUR", FxMath.cross(new BigDecimal("395"), BigDecimal.ONE), 100);
        assertThat(c.mid()).isZero();
        assertThat(c.merchant()).isZero();
    }

    @Test
    void refusesNonsense() {
        assertThatThrownBy(() -> FxMath.convert(0, "EUR", "PLN", BigDecimal.ONE, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxMath.convert(1, "EUR", "PLN", BigDecimal.ONE, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxMath.convert(1, "EUR", "PLN", BigDecimal.ONE, 10_001)).isInstanceOf(IllegalArgumentException.class);
    }
}
