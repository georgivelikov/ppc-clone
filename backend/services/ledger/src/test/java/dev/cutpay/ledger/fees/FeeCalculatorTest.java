package dev.cutpay.ledger.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeeCalculatorTest {

    private final FeeStrategy standard = PricingPlan.standard("mer_1").strategy();

    @Test
    void chargesTheDocumentedFeeOnAHundredEuroConsumerPayment() {
        // 100.00 -> 1.29% = 1.29, plus 0.10 = 1.39, so the merchant nets 98.61
        assertThat(standard.feeFor(10_000, CardProfile.of("consumer", "eea"))).isEqualTo(139);
    }

    @Test
    void chargesTheHigherRateForBusinessAndNonEeaCards() {
        // 100.00 -> 2.69% = 2.69, plus 0.10 = 2.79
        assertThat(standard.feeFor(10_000, CardProfile.of("business", "eea"))).isEqualTo(279);
        assertThat(standard.feeFor(10_000, CardProfile.of("consumer", "non_eea"))).isEqualTo(279);
        assertThat(standard.feeFor(10_000, CardProfile.of("business", "non_eea"))).isEqualTo(279);
    }

    @Test
    void treatsMissingCardDetailsAsAnEeaConsumerCard() {
        assertThat(standard.feeFor(10_000, CardProfile.of(null, null))).isEqualTo(139);
    }

    @ParameterizedTest(name = "{0} minor units at {1} bps rounds to {2}")
    @CsvSource({
            // exact halves round to the even neighbour
            "150, 100, 2",
            "250, 100, 2",
            "350, 100, 4",
            "450, 100, 4",
            // not a tie: ordinary rounding
            "1000, 129, 13",
            "999, 129, 13",
            "1, 129, 0"})
    void roundsHalfEven(long amount, int bps, long expectedFee) {
        assertThat(new Rate(bps, 0).apply(amount)).isEqualTo(expectedFee);
    }

    @Test
    void neverChargesMoreThanThePaymentItself() {
        // 0.05: 1.29% rounds to 0, plus the 0.10 fixed fee would exceed the payment
        assertThat(standard.feeFor(5, CardProfile.of("consumer", "eea"))).isEqualTo(5);
        assertThat(standard.feeFor(10, CardProfile.of("consumer", "eea"))).isEqualTo(10);
        assertThat(standard.feeFor(11, CardProfile.of("consumer", "eea"))).isEqualTo(10);
    }

    @Test
    void handlesLargeAmountsWithoutOverflow() {
        long hundredBillion = 10_000_000_000_000L; // 100 billion in minor units
        assertThat(standard.feeFor(hundredBillion, CardProfile.of("consumer", "eea")))
                .isEqualTo(129_000_000_000L + 10);
    }

    @Test
    void honoursAPerMerchantOverride() {
        var custom = new PricingPlan("mer_2", new Rate(100, 0), new Rate(200, 0), 7).strategy();
        assertThat(custom.feeFor(10_000, CardProfile.of("consumer", "eea"))).isEqualTo(100);
        assertThat(custom.feeFor(10_000, CardProfile.of("business", "eea"))).isEqualTo(200);
    }

    @Test
    void rejectsNonPositiveAmountsAndNegativeRates() {
        assertThatThrownBy(() -> standard.feeFor(0, CardProfile.of("consumer", "eea")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rate(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
