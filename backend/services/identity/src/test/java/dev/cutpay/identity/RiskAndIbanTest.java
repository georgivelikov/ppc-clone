package dev.cutpay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cutpay.identity.onboarding.RiskScorer;
import dev.cutpay.identity.onboarding.RiskScorer.Input;
import org.junit.jupiter.api.Test;

class RiskAndIbanTest {

    private static RiskScorer.Result score(String type, String industry, String country, long volume, String screening, boolean dissolved) {
        return RiskScorer.score(new Input(type, industry, country, volume, screening, dissolved));
    }

    @Test
    void anOrdinaryBusinessScoresLowAndNeedsNoPerson() {
        var result = score("company", "hospitality", "BG", 2_000_000, "clear", false);
        assertThat(result.score()).isEqualTo(10);
        assertThat(result.needsManualReview()).isFalse();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void eachRuleAddsToTheScoreAndSaysWhy() {
        assertThat(score("sole_trader", "retail", "BG", 1_000_000, "clear", false).score()).isEqualTo(15);
        assertThat(score("company", "retail", "GB", 1_000_000, "clear", false).score()).isEqualTo(30);
        assertThat(score("company", "retail", "BG", 20_000_000, "clear", false).score()).isEqualTo(40);
        assertThat(score("company", "retail", "BG", 200_000_000, "clear", false).score()).isEqualTo(70);
        assertThat(score("company", "retail", "BG", 1_000_000, "pep", false).score()).isEqualTo(50);
        assertThat(score("company", "crypto", "BG", 1_000_000, "clear", false).reasons()).containsExactly("Restricted industry: crypto");
    }

    @Test
    void highScoresGoToAPersonAndTheScoreStopsAtOneHundred() {
        assertThat(score("company", "gambling", "BG", 1_000_000, "clear", false).needsManualReview()).isTrue(); // 60
        assertThat(score("company", "retail", "BG", 1_000_000, "pep", false).needsManualReview()).isFalse();     // 50
        assertThat(score("company", "retail", "BG", 1_000_000, "sanctions", false).needsManualReview()).isTrue();
        assertThat(score("company", "gambling", "GB", 200_000_000, "sanctions", true).score()).isEqualTo(100);
        assertThat(score("company", "retail", "BG", 1_000_000, "clear", true).needsManualReview()).isTrue();
    }

    @Test
    void everyListedIndustryIsKnown() {
        assertThat(RiskScorer.INDUSTRIES).contains("retail", "hospitality", "health_beauty", "transport", "charity", "startup");
    }
}
