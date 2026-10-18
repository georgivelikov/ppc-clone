package dev.cutpay.bnpl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Offers from the customer's side and settings and explanations from the merchant's, against a real database. The
 * payments service and the four providers are one WireMock server that answers the way they do.
 */
@SpringBootTest(properties = {"cutpay.bnpl.provider-timeout=PT1.5S", "cutpay.bnpl.offers-deadline=PT2S"})
@AutoConfigureMockMvc
@Testcontainers
class BnplFlowIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final WireMockServer WORLD = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WORLD.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("cutpay.bnpl.payments-url", WORLD::baseUrl);
        registry.add("cutpay.bnpl.providers.splitnow-url", () -> WORLD.baseUrl() + "/splitnow/v1");
        registry.add("cutpay.bnpl.providers.lendo-url", () -> WORLD.baseUrl() + "/lendo/api");
        registry.add("cutpay.bnpl.providers.kreditko-url", () -> WORLD.baseUrl() + "/kreditko");
        registry.add("cutpay.bnpl.providers.paylatereu-url", () -> WORLD.baseUrl() + "/paylatereu/api");
    }

    @AfterAll
    static void stop() {
        WORLD.stop();
    }

    @Autowired
    MockMvc mvc;

    private String merchantId;
    private String secret;

    @BeforeEach
    void freshMerchantAndAnswers() {
        merchantId = "mer_" + UUID.randomUUID().toString().replace("-", "");
        secret = "pay_" + UUID.randomUUID().toString().replace("-", "") + "_secret_x";
        WORLD.resetAll();
        payment(30_000, "requires_payment_method", "automatic");
        providersAnswer();
    }

    private RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId).claim("role", "owner"));
    }

    private void payment(long amount, String status, String captureMethod) {
        WORLD.stubFor(get(urlEqualTo("/internal/checkout/" + secret)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"payment_id":"pay_%s","merchant_id":"%s","livemode":false,"merchant_name":"Rose Cafe",
                         "status":"%s","amount":%d,"currency":"EUR","description":"Order 7",
                         "customer_email":"anna@example.com","capture_method":"%s"}"""
                        .formatted(secret.substring(4, 12), merchantId, status, amount, captureMethod))));
    }

    private String paymentId() {
        return "pay_" + secret.substring(4, 12);
    }

    /** What the four providers answer for a 300.00 price. */
    private void providersAnswer() {
        json(post("/splitnow/v1/quotes"), """
                {"currency":"EUR","plans":[
                  {"instalments":3,"instalment_cents":[10000,10000,10000],"interest_pct":0.0,"merchant_fee_pct":3.5,"merchant_fee_cents":1050},
                  {"instalments":4,"instalment_cents":[7500,7500,7500,7500],"interest_pct":0.0,"merchant_fee_pct":3.5,"merchant_fee_cents":1050}]}""");
        json(get(urlPathEqualTo("/lendo/api/offers")), """
                {"offers":[
                  {"months":6,"monthly":"51.09","apr":9.9,"total_payable":"306.54","merchant_fee":"8.70"},
                  {"months":12,"monthly":"26.38","apr":9.9,"total_payable":"316.56","merchant_fee":"8.70"}]}""");
        json(post("/kreditko/simulations"), """
                {"termMonths":12,"monthlyMinor":2680,"aprPercent":12.9,"totalPayableMinor":32160,"feeMinor":1350}""");
        json(post("/paylatereu/api"), """
                {"ok":true,"code":"OK","data":{"payInDays":30,"customerCost":0.00,"merchantFee":7.50}}""");
    }

    private static void json(com.github.tomakehurst.wiremock.client.MappingBuilder mapping, String body) {
        WORLD.stubFor(mapping.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private ResultActions offers() throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers"));
    }

    private ResultActions put(String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/v1/bnpl/settings").with(merchant()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions decision() throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/routing_decisions/" + paymentId()).with(merchant()));
    }

    // --- the customer's offers ---------------------------------------------------------------------------------

    @Test
    void aThreeHundredEuroOrderGetsOffersFromAllFourProvidersInMinorUnitsWithoutTheMerchantFee() throws Exception {
        String body = offers().andExpect(status().isOk())
                .andExpect(jsonPath("$.payment").value(paymentId())).andExpect(jsonPath("$.amount").value(30000))
                .andExpect(jsonPath("$.country").value("BG")).andExpect(jsonPath("$.routing_mode").value("customer_choice"))
                .andExpect(jsonPath("$.offers.length()").value(8))
                .andExpect(jsonPath("$.offers[0].provider").value("splitnow"))
                .andExpect(jsonPath("$.offers[0].plan").value("splitnow_3"))
                .andExpect(jsonPath("$.offers[0].instalment_amounts[0]").value(10000))
                .andExpect(jsonPath("$.offers[0].customer_cost").value(0))
                .andExpect(jsonPath("$.offers[2].provider").value("lendo"))
                .andExpect(jsonPath("$.offers[2].customer_cost").value(654))
                .andExpect(jsonPath("$.offers[2].apr").value(9.9))
                .andExpect(jsonPath("$.offers[7].provider").value("paylatereu"))
                .andExpect(jsonPath("$.offers[7].interval_days").value(30))
                .andExpect(jsonPath("$.recommended.provider").value("splitnow"))
                .andExpect(jsonPath("$.unavailable.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("merchant_fee");
    }

    @Test
    void aSlowProviderIsLeftOutAndTheCustomerIsNotKeptWaiting() throws Exception {
        WORLD.stubFor(get(urlPathEqualTo("/lendo/api/offers")).willReturn(aResponse().withFixedDelay(5_000)
                .withHeader("Content-Type", "application/json").withBody("{\"offers\":[]}")));

        long started = System.nanoTime();
        offers().andExpect(status().isOk())
                .andExpect(jsonPath("$.offers[?(@.provider == 'lendo')]").isEmpty())
                .andExpect(jsonPath("$.offers[?(@.provider == 'splitnow')]").isNotEmpty())
                .andExpect(jsonPath("$.offers[?(@.provider == 'kreditko')]").isNotEmpty())
                .andExpect(jsonPath("$.unavailable.length()").value(1))
                .andExpect(jsonPath("$.unavailable[0].provider").value("lendo"))
                .andExpect(jsonPath("$.unavailable[0].reason").value("did not answer in time"));
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).isLessThan(2_500);
    }

    @Test
    void aProviderThatIsDownOrRefusesIsLeftOutAndTheRestStillWork() throws Exception {
        WORLD.stubFor(post(urlEqualTo("/kreditko/simulations")).willReturn(aResponse().withStatus(503)));
        WORLD.stubFor(post(urlEqualTo("/paylatereu/api")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":false,\"code\":\"AMOUNT_RANGE\",\"data\":null}")));

        offers().andExpect(status().isOk()).andExpect(jsonPath("$.offers.length()").value(4))
                .andExpect(jsonPath("$.unavailable.length()").value(2))
                .andExpect(jsonPath("$.unavailable[?(@.provider == 'kreditko')].reason").value("is not responding"))
                .andExpect(jsonPath("$.unavailable[?(@.provider == 'paylatereu')].reason").value("declined to quote"));
    }

    @Test
    void providersThatCannotFinanceThePriceAreNotAskedAtAll() throws Exception {
        payment(90_000, "requires_payment_method", "automatic");

        offers().andExpect(status().isOk()).andExpect(jsonPath("$.offers[?(@.provider == 'paylatereu')]").isEmpty())
                .andExpect(jsonPath("$.unavailable.length()").value(0));
        WORLD.verify(0, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/paylatereu/api")));
        decision().andExpect(jsonPath("$.excluded[?(@.provider == 'paylatereu')].code").value("above_maximum"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("PayLaterEU excluded: amount above €800.00")));
    }

    @Test
    void aPaymentThatCannotBePaidInInstalmentsIsRefused() throws Exception {
        payment(30_000, "succeeded", "automatic");
        offers().andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("payment_not_payable"));

        payment(30_000, "requires_payment_method", "manual");
        offers().andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("bnpl_not_available"));

        WORLD.stubFor(get(urlEqualTo("/internal/checkout/" + secret)).willReturn(aResponse().withStatus(404)));
        offers().andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("session_not_found"));
    }

    @Test
    void theCountryChangesWhoMayBeAsked() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers").param("country", "nl")).andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("NL")).andExpect(jsonPath("$.offers[?(@.provider == 'lendo')]").isEmpty())
                .andExpect(jsonPath("$.offers[?(@.provider == 'kreditko')]").isEmpty())
                .andExpect(jsonPath("$.offers[?(@.provider == 'splitnow')]").isNotEmpty());
    }

    // --- the merchant's settings and the explanation -----------------------------------------------------------

    @Test
    void aNewMerchantHasEverythingOnInCatalogueOrderWithCustomerChoice() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/settings").with(merchant())).andExpect(status().isOk())
                .andExpect(jsonPath("$.routing_mode").value("customer_choice"))
                .andExpect(jsonPath("$.providers.length()").value(4))
                .andExpect(jsonPath("$.providers[0].code").value("splitnow"))
                .andExpect(jsonPath("$.providers[0].enabled").value(true))
                .andExpect(jsonPath("$.providers[3].code").value("paylatereu"))
                .andExpect(jsonPath("$.providers[1].min_amount").value(10000))
                .andExpect(jsonPath("$.weights.cost").value(1));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void cheapestRoutingPicksTheProviderThatKeepsLeastAndSwitchingItOffChangesTheDecisionAndTheExplanation() throws Exception {
        put("{\"routing_mode\":\"cheapest\"}").andExpect(status().isOk()).andExpect(jsonPath("$.routing_mode").value("cheapest"));

        offers().andExpect(jsonPath("$.recommended.provider").value("paylatereu"))
                .andExpect(jsonPath("$.recommended.plan").value("paylatereu_30"))
                .andExpect(jsonPath("$.offers[0].provider").value("paylatereu"));
        decision().andExpect(status().isOk()).andExpect(jsonPath("$.chosen_provider").value("paylatereu"))
                .andExpect(jsonPath("$.strategy").value("cheapest"))
                .andExpect(jsonPath("$.reason").value("Chose PayLaterEU: cheapest eligible (2.5% vs 2.9% for Lendo)"))
                .andExpect(jsonPath("$.ranked.length()").value(4)).andExpect(jsonPath("$.ranked[0].rank").value(1))
                .andExpect(jsonPath("$.ranked[0].best.merchant_fee").value(750));

        put("{\"providers\":[{\"code\":\"paylatereu\",\"enabled\":false}]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[?(@.code == 'paylatereu')].enabled").value(false));
        offers().andExpect(jsonPath("$.recommended.provider").value("lendo"));
        decision().andExpect(jsonPath("$.chosen_provider").value("lendo")).andExpect(jsonPath("$.reason").value(
                "Chose Lendo: cheapest eligible (2.9% vs 3.5% for SplitNow); PayLaterEU excluded: switched off in your settings"));
    }

    @Test
    void theMerchantsOrderDecidesCustomerChoiceAndPartialUpdatesKeepTheRest() throws Exception {
        put("{\"providers\":[{\"code\":\"kreditko\",\"priority\":0},{\"code\":\"lendo\",\"priority\":1}]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].code").value("kreditko")).andExpect(jsonPath("$.providers[1].code").value("lendo"));
        put("{\"weights\":{\"cost\":3,\"speed\":0,\"approval\":1}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].code").value("kreditko")).andExpect(jsonPath("$.weights.cost").value(3))
                .andExpect(jsonPath("$.routing_mode").value("customer_choice"));

        offers().andExpect(jsonPath("$.offers[0].provider").value("kreditko")).andExpect(jsonPath("$.recommended.provider").value("kreditko"));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/settings").with(merchant())).andExpect(jsonPath("$.providers[0].code").value("kreditko"));
    }

    @Test
    void settingsThatMakeNoSenseAreRefused() throws Exception {
        put("{\"routing_mode\":\"random\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_routing_mode"));
        put("{\"providers\":[{\"code\":\"klarna\",\"enabled\":true}]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unknown_provider"));
        put("{\"providers\":[{\"code\":\"lendo\"},{\"code\":\"lendo\"}]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("duplicate_provider"));
        put("{\"providers\":[{\"code\":\"lendo\",\"priority\":1},{\"code\":\"kreditko\",\"priority\":1}]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("duplicate_priority"));
        put("{\"weights\":{\"cost\":0,\"speed\":0,\"approval\":0}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_weights"));
        put("{\"weights\":{\"cost\":-1,\"speed\":2,\"approval\":1}}").andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/settings").with(merchant())).andExpect(jsonPath("$.routing_mode").value("customer_choice"));
    }

    @Test
    void theLatestDecisionOfAPaymentIsTheOneExplainedAndOnlyItsMerchantCanReadIt() throws Exception {
        decision().andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("routing_decision_not_found"));

        offers().andExpect(status().isOk());
        put("{\"routing_mode\":\"fastest\"}");
        offers().andExpect(status().isOk());

        decision().andExpect(jsonPath("$.strategy").value("fastest")).andExpect(jsonPath("$.chosen_provider").value("splitnow"))
                .andExpect(jsonPath("$.reason").value("Chose SplitNow: fastest to decide (about 300 ms vs 600 ms for PayLaterEU)"));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/routing_decisions/" + paymentId()).with(jwt().jwt(j -> j.claim("mid", "mer_other"))))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/routing_decisions/" + paymentId()).with(merchant()).param("livemode", "true"))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void eachMerchantHasItsOwnSettings() throws Exception {
        put("{\"routing_mode\":\"weighted\"}").andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/settings").with(jwt().jwt(j -> j.claim("mid", "mer_other"))))
                .andExpect(jsonPath("$.routing_mode").value("customer_choice"));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/settings").with(merchant()).param("livemode", "true"))
                .andExpect(jsonPath("$.routing_mode").value("customer_choice"));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/settings").with(merchant())).andExpect(jsonPath("$.routing_mode").value("weighted"));
    }
}
