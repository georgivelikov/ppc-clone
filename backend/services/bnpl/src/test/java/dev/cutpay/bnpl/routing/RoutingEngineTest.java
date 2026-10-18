package dev.cutpay.bnpl.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.routing.Decision.Ranked;
import dev.cutpay.bnpl.routing.MerchantRouting.ProviderSetting;
import dev.cutpay.bnpl.routing.MerchantRouting.Weights;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RoutingEngineTest {

    private static final PriorStats STATS = new PriorStats();

    private static MerchantRouting routing(RoutingMode mode) {
        return MerchantRouting.withSettings(mode, Weights.EQUAL, List.of());
    }

    private static MerchantRouting routing(RoutingMode mode, Weights weights, ProviderSetting... settings) {
        return MerchantRouting.withSettings(mode, weights, List.of(settings));
    }

    private static Offer offer(String provider, int count, long fee) {
        return new Offer(provider, provider + "_" + count, count, List.of(), 14, 0, 0, fee, "EUR");
    }

    /** What the real providers quote for a 300.00 price: SplitNow 3.5%, Lendo 2.9%, KreditKo 4.5%, PayLaterEU 2.5%. */
    private static Map<String, List<Offer>> quotesFor300() {
        return Map.of(
                "splitnow", List.of(offer("splitnow", 3, 1050), offer("splitnow", 4, 1050)),
                "lendo", List.of(offer("lendo", 6, 870), offer("lendo", 12, 870)),
                "kreditko", List.of(offer("kreditko", 12, 1350)),
                "paylatereu", List.of(offer("paylatereu", 1, 750)));
    }

    private static Decision decide(MerchantRouting routing, long amount) {
        Eligibility eligibility = RoutingEngine.eligible(amount, "EUR", "BG", routing, Set.of());
        return RoutingEngine.decide(routing, amount, "EUR", eligibility, quotesFor300(), Map.of(), STATS::stats);
    }

    // --- eligibility -------------------------------------------------------------------------------------------

    static Stream<Arguments> eligibilityCases() {
        return Stream.of(
                Arguments.of("a 300.00 order in Bulgaria can use all four", 30_000, "EUR", "BG", Map.of(), Set.of(), Map.of()),
                Arguments.of("above 800.00 PayLaterEU is out", 90_000, "EUR", "BG", Map.of(), Set.of(), Map.of("paylatereu", "above_maximum")),
                Arguments.of("below 100.00 Lendo and KreditKo are out", 9_000, "EUR", "BG", Map.of(), Set.of(),
                        Map.of("lendo", "below_minimum", "kreditko", "below_minimum")),
                Arguments.of("below 20.00 nobody is left", 1_500, "EUR", "BG", Map.of(), Set.of(),
                        Map.of("splitnow", "below_minimum", "lendo", "below_minimum", "kreditko", "below_minimum", "paylatereu", "below_minimum")),
                Arguments.of("above 1500.00 SplitNow and PayLaterEU are out", 200_000, "EUR", "BG", Map.of(), Set.of(),
                        Map.of("splitnow", "above_maximum", "paylatereu", "above_maximum")),
                Arguments.of("in the Netherlands Lendo and KreditKo do not operate", 30_000, "EUR", "NL", Map.of(), Set.of(),
                        Map.of("lendo", "country", "kreditko", "country")),
                Arguments.of("in dollars nobody works", 30_000, "USD", "BG", Map.of(), Set.of(),
                        Map.of("splitnow", "currency", "lendo", "currency", "kreditko", "currency", "paylatereu", "currency")),
                Arguments.of("a provider the merchant switched off is out", 30_000, "EUR", "BG", Map.of("splitnow", false), Set.of(),
                        Map.of("splitnow", "disabled")),
                Arguments.of("a provider with an open circuit is out", 30_000, "EUR", "BG", Map.of(), Set.of("paylatereu"),
                        Map.of("paylatereu", "unavailable")),
                Arguments.of("exactly at a limit still counts", 5_000, "EUR", "BG", Map.of(), Set.of(),
                        Map.of("lendo", "below_minimum", "kreditko", "below_minimum")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eligibilityCases")
    void eligibilityFiltersByMerchantCurrencyCountryLimitsAndHealth(String name, long amount, String currency, String country,
                                                                     Map<String, Boolean> enabled, Set<String> down,
                                                                     Map<String, String> expectedExclusions) {
        MerchantRouting routing = MerchantRouting.withSettings(RoutingMode.CHEAPEST, Weights.EQUAL,
                enabled.entrySet().stream().map(e -> new ProviderSetting(e.getKey(), e.getValue(), 0)).toList());

        Eligibility result = RoutingEngine.eligible(amount, currency, country, routing, down);

        if (expectedExclusions.isEmpty()) {
            assertThat(result.excluded()).isEmpty();
        } else {
            assertThat(result.excluded()).extracting(Eligibility.Exclusion::provider, Eligibility.Exclusion::code)
                    .containsExactlyInAnyOrder(expectedExclusions.entrySet().stream()
                            .map(e -> org.assertj.core.groups.Tuple.tuple(e.getKey(), e.getValue())).toArray(org.assertj.core.groups.Tuple[]::new));
            assertThat(result.eligible()).extracting(ProviderInfo::code).doesNotContain(expectedExclusions.keySet().toArray(String[]::new));
        }
        assertThat(result.eligible().size() + result.excluded().size()).isEqualTo(4);
    }

    @Test
    void exclusionsSayWhyInWordsAPersonCanRead() {
        Eligibility result = RoutingEngine.eligible(9_000, "EUR", "NL", routing(RoutingMode.CHEAPEST), Set.of());

        assertThat(result.excluded()).extracting(Eligibility.Exclusion::message).containsExactly(
                "not available in NL", "not available in NL");
        assertThat(RoutingEngine.eligible(9_000, "EUR", "BG", routing(RoutingMode.CHEAPEST), Set.of()).excluded())
                .extracting(Eligibility.Exclusion::message).containsExactly("amount below €100.00", "amount below €200.00");
    }

    // --- ranking -----------------------------------------------------------------------------------------------

    static Stream<Arguments> orderings() {
        return Stream.of(
                Arguments.of(RoutingMode.CUSTOMER_CHOICE, List.of("splitnow", "lendo", "kreditko", "paylatereu")),
                Arguments.of(RoutingMode.CHEAPEST, List.of("paylatereu", "lendo", "splitnow", "kreditko")),
                Arguments.of(RoutingMode.FASTEST, List.of("splitnow", "paylatereu", "lendo", "kreditko")),
                Arguments.of(RoutingMode.BEST_APPROVAL, List.of("splitnow", "paylatereu", "lendo", "kreditko")),
                Arguments.of(RoutingMode.WEIGHTED, List.of("paylatereu", "splitnow", "lendo", "kreditko")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    void eachModeRanksByItsOwnRule(RoutingMode mode, List<String> expectedOrder) {
        Decision decision = decide(routing(mode), 30_000);

        assertThat(decision.ranked()).extracting(Ranked::provider).containsExactlyElementsOf(expectedOrder);
        assertThat(decision.chosen()).isEqualTo(expectedOrder.get(0));
        assertThat(decision.ranked()).extracting(Ranked::rank).containsExactly(1, 2, 3, 4);
    }

    @Test
    void theWeightedModeFollowsTheWeightsTheMerchantSets() {
        assertThat(decide(routing(RoutingMode.WEIGHTED, new Weights(1, 0, 0)), 30_000).chosen()).isEqualTo("paylatereu");
        assertThat(decide(routing(RoutingMode.WEIGHTED, new Weights(0, 1, 0)), 30_000).chosen()).isEqualTo("splitnow");
        assertThat(decide(routing(RoutingMode.WEIGHTED, new Weights(0, 0, 1)), 30_000).chosen()).isEqualTo("splitnow");
        // Cost and approval together: SplitNow approves most, PayLaterEU is cheapest and nearly as good at approving.
        assertThat(decide(routing(RoutingMode.WEIGHTED, new Weights(3, 0, 1)), 30_000).chosen()).isEqualTo("paylatereu");
        assertThat(decide(routing(RoutingMode.WEIGHTED, new Weights(0, 0, 0)), 30_000).ranked()).hasSize(4);
    }

    @Test
    void theMerchantsOwnOrderDecidesInCustomerChoiceAndBreaksTies() {
        MerchantRouting kreditkoFirst = routing(RoutingMode.CUSTOMER_CHOICE, Weights.EQUAL,
                new ProviderSetting("kreditko", true, 0), new ProviderSetting("lendo", true, 1));

        assertThat(decide(kreditkoFirst, 30_000).ranked()).extracting(Ranked::provider)
                .containsExactly("kreditko", "lendo", "splitnow", "paylatereu");

        Map<String, List<Offer>> sameFee = Map.of("splitnow", List.of(offer("splitnow", 3, 900)),
                "lendo", List.of(offer("lendo", 6, 900)));
        MerchantRouting lendoFirst = routing(RoutingMode.CHEAPEST, Weights.EQUAL, new ProviderSetting("lendo", true, 0));
        Eligibility eligibility = RoutingEngine.eligible(30_000, "EUR", "BG", lendoFirst, Set.of());
        Decision tie = RoutingEngine.decide(lendoFirst, 30_000, "EUR", eligibility, sameFee, Map.of(), STATS::stats);
        assertThat(tie.chosen()).isEqualTo("lendo");
    }

    @Test
    void aProviderIsJudgedByItsCheapestOffer() {
        Decision decision = decide(routing(RoutingMode.CHEAPEST), 30_000);

        assertThat(decision.ranked().get(0).best().merchantFee()).isEqualTo(750);
        Map<String, List<Offer>> mixed = Map.of("lendo", List.of(offer("lendo", 24, 900), offer("lendo", 6, 700)),
                "splitnow", List.of(offer("splitnow", 3, 800)));
        Eligibility eligibility = RoutingEngine.eligible(30_000, "EUR", "BG", routing(RoutingMode.CHEAPEST), Set.of());
        Decision picked = RoutingEngine.decide(routing(RoutingMode.CHEAPEST), 30_000, "EUR", eligibility, mixed, Map.of(), STATS::stats);
        assertThat(picked.chosen()).isEqualTo("lendo");
        assertThat(picked.ranked().get(0).best().plan()).isEqualTo("lendo_6");
        assertThat(picked.ranked().get(0).offers()).extracting(Offer::instalmentCount).containsExactly(6, 24);
    }

    // --- explaining --------------------------------------------------------------------------------------------

    @Test
    void theReasonNamesTheWinnerTheRunnerUpAndWhoWasLeftOut() {
        Decision decision = decide(routing(RoutingMode.CHEAPEST), 9_000 + 21_000);
        assertThat(decision.reason()).isEqualTo("Chose PayLaterEU: cheapest eligible (2.5% vs 2.9% for Lendo)");

        MerchantRouting noPayLater = routing(RoutingMode.CHEAPEST, Weights.EQUAL, new ProviderSetting("paylatereu", false, 9));
        assertThat(decide(noPayLater, 30_000).reason())
                .isEqualTo("Chose Lendo: cheapest eligible (2.9% vs 3.5% for SplitNow); PayLaterEU excluded: switched off in your settings");

        Decision big = decide(routing(RoutingMode.CHEAPEST), 90_000);
        assertThat(big.reason()).contains("PayLaterEU excluded: amount above €800.00");
    }

    @Test
    void everyModeExplainsItselfInItsOwnTerms() {
        assertThat(decide(routing(RoutingMode.FASTEST), 30_000).reason())
                .isEqualTo("Chose SplitNow: fastest to decide (about 300 ms vs 600 ms for PayLaterEU)");
        assertThat(decide(routing(RoutingMode.BEST_APPROVAL), 30_000).reason())
                .isEqualTo("Chose SplitNow: most likely to approve (85% vs 80% for PayLaterEU)");
        assertThat(decide(routing(RoutingMode.WEIGHTED, new Weights(2, 1, 1)), 30_000).reason())
                .startsWith("Chose PayLaterEU: best weighted score (cost 2, speed 1, approval 1)");
        assertThat(decide(routing(RoutingMode.CUSTOMER_CHOICE), 30_000).reason())
                .isEqualTo("Chose SplitNow: first in your order; the customer picks from the offers shown");
        assertThat(decide(routing(RoutingMode.FASTEST,  Weights.EQUAL, new ProviderSetting("splitnow", false, 9),
                new ProviderSetting("paylatereu", false, 9)), 30_000).reason())
                .startsWith("Chose Lendo: fastest to decide (about 12 s vs 45 s for KreditKo)");
    }

    @Test
    void aProviderThatCouldNotQuoteIsLeftOutWithWhy() {
        MerchantRouting routing = routing(RoutingMode.CHEAPEST);
        Eligibility eligibility = RoutingEngine.eligible(30_000, "EUR", "BG", routing, Set.of());
        Map<String, List<Offer>> answered = Map.of("splitnow", List.of(offer("splitnow", 3, 1050)),
                "paylatereu", List.of(offer("paylatereu", 1, 750)));

        Decision decision = RoutingEngine.decide(routing, 30_000, "EUR", eligibility, answered,
                Map.of("lendo", "did not answer in time", "kreditko", "is not responding"), STATS::stats);

        assertThat(decision.chosen()).isEqualTo("paylatereu");
        assertThat(decision.excluded()).extracting(Eligibility.Exclusion::provider, Eligibility.Exclusion::code, Eligibility.Exclusion::message)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("lendo", "no_quote", "did not answer in time"),
                        org.assertj.core.groups.Tuple.tuple("kreditko", "no_quote", "is not responding"));
        assertThat(decision.reason()).contains("Lendo excluded: did not answer in time").contains("KreditKo excluded: is not responding");
    }

    @Test
    void withNobodyLeftTheDecisionSaysSoAndChoosesNoOne() {
        Decision decision = decide(routing(RoutingMode.CHEAPEST), 1_500);

        assertThat(decision.chosen()).isNull();
        assertThat(decision.ranked()).isEmpty();
        assertThat(decision.reason()).startsWith("No provider can finance this payment; SplitNow excluded: amount below €50.00");
    }

    @Test
    void feesAreShownAsAShareOfThePrice() {
        assertThat(RoutingEngine.percent(1050, 30_000)).isEqualTo("3.5%");
        assertThat(RoutingEngine.percent(870, 30_000)).isEqualTo("2.9%");
        assertThat(RoutingEngine.percent(1, 3)).isEqualTo("33.3%");
        assertThat(RoutingEngine.percent(300, 30_000)).isEqualTo("1%");
    }
}
