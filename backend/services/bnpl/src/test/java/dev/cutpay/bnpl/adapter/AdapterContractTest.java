package dev.cutpay.bnpl.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import dev.cutpay.bnpl.model.QuoteRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Each adapter against a stand-in that answers exactly as the provider does (the answers below are what bnpl-sim
 * returns, see ADR 0017): what comes back must be Cutpay offers in minor units, whatever the provider's units, and
 * every kind of failure must become a {@link ProviderException} of the right kind.
 */
class AdapterContractTest {

    static WireMockServer provider = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static BnplProperties properties;

    @BeforeAll
    static void start() {
        provider.start();
        String base = provider.baseUrl();
        properties = new BnplProperties("http://unused", Duration.ofSeconds(2), Duration.ofMillis(600), "BG", "http://localhost:8090",
                Duration.ofSeconds(5), Duration.ofHours(24), Duration.ofDays(1), List.of(6, 12, 24),
                new BnplProperties.Providers(base + "/splitnow/v1", base + "/lendo/api", base + "/kreditko", base + "/paylatereu/api",
                        "lendo_test_secret"));
    }

    @AfterAll
    static void stop() {
        provider.stop();
    }

    @BeforeEach
    void reset() {
        provider.resetAll();
    }

    private static final QuoteRequest THREE_HUNDRED = new QuoteRequest(30_000, "EUR", "BG");

    private static void json(com.github.tomakehurst.wiremock.client.MappingBuilder mapping, int status, String body) {
        provider.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    // --- SplitNow ----------------------------------------------------------------------------------------------

    private SplitNowAdapter splitnow() {
        return new SplitNowAdapter(properties, RestClient.builder());
    }

    @Test
    void splitNowPlansBecomeOffersInCents() {
        json(post("/splitnow/v1/quotes"), 200, """
                {"currency":"EUR","plans":[
                  {"instalments":3,"instalment_cents":[10000,10000,10000],"interest_pct":0.0,"merchant_fee_pct":3.5,"merchant_fee_cents":1050},
                  {"instalments":4,"instalment_cents":[7500,7500,7500,7500],"interest_pct":0.0,"merchant_fee_pct":3.5,"merchant_fee_cents":1050}]}""");

        List<Offer> offers = splitnow().quote(THREE_HUNDRED);

        assertThat(offers).extracting(Offer::plan).containsExactly("splitnow_3", "splitnow_4");
        Offer three = offers.get(0);
        assertThat(three.provider()).isEqualTo("splitnow");
        assertThat(three.instalments()).containsExactly(10_000L, 10_000L, 10_000L);
        assertThat(three.totalPayable()).isEqualTo(30_000);
        assertThat(three.customerCost(30_000)).isZero();
        assertThat(three.merchantFee()).isEqualTo(1050);
        assertThat(three.aprPercent()).isZero();
        assertThat(three.intervalDays()).isEqualTo(14);
        provider.verify(postRequestedFor(urlEqualTo("/splitnow/v1/quotes"))
                .withRequestBody(equalToJson("{\"amount_cents\":30000,\"currency\":\"EUR\"}")));
    }

    @Test
    void splitNowFailuresAreToldApart() {
        json(post("/splitnow/v1/quotes"), 422, "{\"error\":{\"type\":\"amount_out_of_range\",\"message\":\"no\"}}");
        assertFailure(() -> splitnow().quote(THREE_HUNDRED), Kind.REFUSED);

        json(post("/splitnow/v1/quotes"), 503, "{}");
        assertFailure(() -> splitnow().quote(THREE_HUNDRED), Kind.UNAVAILABLE);

        json(post("/splitnow/v1/quotes"), 504, "{}");
        assertFailure(() -> splitnow().quote(THREE_HUNDRED), Kind.TIMEOUT);

        json(post("/splitnow/v1/quotes"), 200, "{\"currency\":\"EUR\"}");
        assertFailure(() -> splitnow().quote(THREE_HUNDRED), Kind.INVALID_RESPONSE);

        provider.stubFor(post("/splitnow/v1/quotes").willReturn(aResponse().withStatus(200).withFixedDelay(1500)));
        assertFailure(() -> splitnow().quote(THREE_HUNDRED), Kind.TIMEOUT);
    }

    // --- Lendo -------------------------------------------------------------------------------------------------

    private LendoAdapter lendo() {
        return new LendoAdapter(properties, RestClient.builder());
    }

    @Test
    void lendoDecimalStringsInEurosBecomeMinorUnits() {
        json(get(urlPathEqualTo("/lendo/api/offers")), 200, """
                {"offers":[
                  {"months":6,"monthly":"51.09","apr":9.9,"total_payable":"306.54","merchant_fee":"8.70"},
                  {"months":12,"monthly":"26.38","apr":9.9,"total_payable":"316.56","merchant_fee":"8.70"},
                  {"months":24,"monthly":"13.80","apr":9.9,"total_payable":"331.20","merchant_fee":"8.70"}]}""");

        List<Offer> offers = lendo().quote(THREE_HUNDRED);

        assertThat(offers).extracting(Offer::plan).containsExactly("lendo_6", "lendo_12", "lendo_24");
        Offer six = offers.get(0);
        assertThat(six.instalments()).hasSize(6).allMatch(amount -> amount == 5109);
        assertThat(six.totalPayable()).isEqualTo(30_654);
        assertThat(six.customerCost(30_000)).isEqualTo(654);
        assertThat(six.merchantFee()).isEqualTo(870);
        assertThat(six.aprPercent()).isEqualTo(9.9);
        assertThat(six.intervalDays()).isEqualTo(30);
        provider.verify(getRequestedFor(urlPathEqualTo("/lendo/api/offers")).withQueryParam("amount", com.github.tomakehurst.wiremock.client.WireMock.equalTo("300.00"))
                .withQueryParam("currency", com.github.tomakehurst.wiremock.client.WireMock.equalTo("EUR")));
    }

    @Test
    void lendoFailuresAreToldApart() {
        json(get(urlPathEqualTo("/lendo/api/offers")), 422, "{\"error\":\"amount_out_of_range\"}");
        assertFailure(() -> lendo().quote(THREE_HUNDRED), Kind.REFUSED);

        json(get(urlPathEqualTo("/lendo/api/offers")), 200, "{\"something\":\"else\"}");
        assertFailure(() -> lendo().quote(THREE_HUNDRED), Kind.INVALID_RESPONSE);

    }

    @Test
    void aProviderThatCannotBeReachedIsUnavailable() {
        var nowhere = new BnplProperties("http://unused", Duration.ofSeconds(2), Duration.ofMillis(600), "BG", "http://localhost:8090",
                Duration.ofSeconds(5), Duration.ofHours(24), Duration.ofDays(1), List.of(6),
                new BnplProperties.Providers("http://127.0.0.1:1/splitnow/v1", "http://127.0.0.1:1/lendo/api",
                        "http://127.0.0.1:1/kreditko", "http://127.0.0.1:1/paylatereu/api", "lendo_test_secret"));

        assertFailure(() -> new SplitNowAdapter(nowhere, RestClient.builder()).quote(THREE_HUNDRED), Kind.UNAVAILABLE);
        assertFailure(() -> new LendoAdapter(nowhere, RestClient.builder()).quote(THREE_HUNDRED), Kind.UNAVAILABLE);
        assertFailure(() -> new KreditKoAdapter(nowhere, RestClient.builder()).quote(THREE_HUNDRED), Kind.UNAVAILABLE);
        assertFailure(() -> new PayLaterEuAdapter(nowhere, RestClient.builder()).quote(THREE_HUNDRED), Kind.UNAVAILABLE);
    }

    // --- KreditKo ----------------------------------------------------------------------------------------------

    private KreditKoAdapter kreditko() {
        return new KreditKoAdapter(properties, RestClient.builder());
    }

    private static void simulation(int term, long monthly, long total, long fee) {
        json(post("/kreditko/simulations").withRequestBody(matchingJsonPath("$[?(@.termMonths == " + term + ")]")), 200,
                "{\"termMonths\":%d,\"monthlyMinor\":%d,\"aprPercent\":12.9,\"totalPayableMinor\":%d,\"feeMinor\":%d}"
                        .formatted(term, monthly, total, fee));
    }

    @Test
    void kreditKoPricesEveryTermWithOneCallEach() {
        simulation(6, 5170, 31_020, 1350);
        simulation(12, 2680, 32_160, 1350);
        simulation(24, 1430, 34_320, 1350);

        List<Offer> offers = kreditko().quote(THREE_HUNDRED);

        assertThat(offers).extracting(Offer::plan).containsExactly("kreditko_6", "kreditko_12", "kreditko_24");
        Offer twelve = offers.get(1);
        assertThat(twelve.instalments()).hasSize(12).allMatch(amount -> amount == 2680);
        assertThat(twelve.totalPayable()).isEqualTo(32_160);
        assertThat(twelve.merchantFee()).isEqualTo(1350);
        assertThat(twelve.aprPercent()).isEqualTo(12.9);
        provider.verify(3, postRequestedFor(urlEqualTo("/kreditko/simulations")));
        provider.verify(postRequestedFor(urlEqualTo("/kreditko/simulations")).withRequestBody(equalToJson("{\"amountMinor\":30000,\"termMonths\":24}")));
    }

    @Test
    void kreditKoFailsAsAWholeWhenOneTermCannotBePriced() {
        simulation(6, 5170, 31_020, 1350);
        simulation(12, 2680, 32_160, 1350);
        json(post("/kreditko/simulations").withRequestBody(matchingJsonPath("$[?(@.termMonths == 24)]")), 503, "{}");

        assertFailure(() -> kreditko().quote(THREE_HUNDRED), Kind.UNAVAILABLE);

        json(post("/kreditko/simulations"), 422, "{\"code\":\"AMOUNT_OUT_OF_RANGE\"}");
        assertFailure(() -> kreditko().quote(THREE_HUNDRED), Kind.REFUSED);
    }

    // --- PayLaterEU --------------------------------------------------------------------------------------------

    private PayLaterEuAdapter paylater() {
        return new PayLaterEuAdapter(properties, RestClient.builder());
    }

    @Test
    void payLaterEuRpcAnswerBecomesASingleOfferOfThirtyDays() {
        json(post("/paylatereu/api"), 200, "{\"ok\":true,\"code\":\"OK\",\"data\":{\"payInDays\":30,\"customerCost\":0.00,\"merchantFee\":7.50}}");

        List<Offer> offers = paylater().quote(THREE_HUNDRED);

        assertThat(offers).hasSize(1);
        Offer offer = offers.get(0);
        assertThat(offer.plan()).isEqualTo("paylatereu_30");
        assertThat(offer.instalments()).containsExactly(30_000L);
        assertThat(offer.intervalDays()).isEqualTo(30);
        assertThat(offer.totalPayable()).isEqualTo(30_000);
        assertThat(offer.merchantFee()).isEqualTo(750);
        provider.verify(postRequestedFor(urlEqualTo("/paylatereu/api"))
                .withRequestBody(equalToJson("{\"action\":\"quote\",\"amount\":300.00,\"currency\":\"EUR\"}", true, true)));
    }

    @Test
    void payLaterEuRefusalsComeAsHttp200WithOkFalseAndAreStillRefusals() {
        json(post("/paylatereu/api"), 200, "{\"ok\":false,\"code\":\"AMOUNT_RANGE\",\"data\":null}");
        assertThatThrownBy(() -> paylater().quote(THREE_HUNDRED)).isInstanceOfSatisfying(ProviderException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.REFUSED);
            assertThat(e.getMessage()).contains("AMOUNT_RANGE");
        });

        json(post("/paylatereu/api"), 200, "{\"hello\":1}");
        assertFailure(() -> paylater().quote(THREE_HUNDRED), Kind.INVALID_RESPONSE);

        json(post("/paylatereu/api"), 504, "{}");
        assertFailure(() -> paylater().quote(THREE_HUNDRED), Kind.TIMEOUT);
    }

    private static void assertFailure(Runnable call, Kind kind) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }
}
