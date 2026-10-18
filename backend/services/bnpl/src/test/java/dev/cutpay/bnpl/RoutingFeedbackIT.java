package dev.cutpay.bnpl;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * What the router does with what it learns and with failures: the next best provider with the customer's consent (at most
 * two more), decisions that move the ranking, and the merchant's analytics.
 */
class RoutingFeedbackIT extends BnplIntegrationTest {

    private static final String EVERYTHING = "{\"email\":\"anna@example.com\",\"birthdate\":\"1990-01-15\","
            + "\"national_id\":\"AB123456\",\"monthly_income\":300000}";

    private void setRouting(String body) throws Exception {
        mvc.perform(MockMvcRequestBuilders.put("/v1/bnpl/settings").with(merchant()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private ResultActions applyBest(String applicant, boolean fallback) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/checkout/" + secret + "/bnpl/apply_best").contentType(MediaType.APPLICATION_JSON)
                .content("{\"applicant\":%s,\"fallback\":%s}".formatted(applicant, fallback)));
    }

    private static void payLaterAuthorises(String state) {
        // The authorise call is the one that names an action of that kind; the quote stays as it was.
        String reply = "DECLINED".equals(state)
                ? "{\"ok\":false,\"code\":\"DECLINED\",\"data\":{\"authId\":\"PL1\",\"state\":\"DECLINED\"}}"
                : "{\"ok\":true,\"code\":\"OK\",\"data\":{\"authId\":\"PL1\",\"state\":\"AUTHORISED\"}}";
        json(post("/paylatereu/api").withRequestBody(containing("\"action\":\"authorise\"")).atPriority(1), reply);
    }

    private static void splitNowDeclines() {
        json(post("/splitnow/v1/checkouts"), 201, "{\"id\":\"sn_1\",\"state\":\"declined\",\"decline_reason\":\"risk_check_failed\"}");
    }

    private static void kreditkoRejects() {
        json(post("/kreditko/loans"), 201, "{\"loanId\":\"KK-1\",\"status\":\"REJECTED\",\"reason\":\"insufficient_income\"}");
    }

    // --- the next best provider --------------------------------------------------------------------------------

    @Test
    void aDeclineMovesOnToTheNextBestProviderWhenTheCustomerAgreed() throws Exception {
        setRouting("{\"routing_mode\":\"cheapest\"}");
        payLaterAuthorises("DECLINED");
        json(post("/lendo/api/applications"), 202, "{\"application_id\":\"ld_1\",\"status\":\"processing\"}");

        applyBest(EVERYTHING, true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].provider").value("paylatereu")).andExpect(jsonPath("$.attempts[0].status").value("declined"))
                .andExpect(jsonPath("$.attempts[1].provider").value("lendo")).andExpect(jsonPath("$.attempts[1].status").value("pending_review"))
                .andExpect(jsonPath("$.application.provider").value("lendo")).andExpect(jsonPath("$.application.status").value("pending_review"));
        assertThat(jdbc.sql("SELECT provider || ':' || status FROM bnpl_applications WHERE payment_id = :p ORDER BY created_at")
                .param("p", paymentId()).query(String.class).list()).containsExactly("paylatereu:declined", "lendo:pending_review");
    }

    @Test
    void withoutConsentOnlyTheBestProviderIsTried() throws Exception {
        setRouting("{\"routing_mode\":\"cheapest\"}");
        payLaterAuthorises("DECLINED");

        applyBest(EVERYTHING, false).andExpect(status().isCreated()).andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.application.status").value("declined"));
        WORLD.verify(0, postRequestedFor(urlEqualTo("/lendo/api/applications")));
    }

    @Test
    void atMostTwoMoreProvidersAreTriedAfterTheFirst() throws Exception {
        setRouting("{\"routing_mode\":\"cheapest\"}");
        payLaterAuthorises("DECLINED");
        json(post("/lendo/api/applications"), 422, "{\"error\":\"amount_out_of_range\"}");
        splitNowDeclines();
        kreditkoRejects();

        applyBest(EVERYTHING, true).andExpect(status().isCreated()).andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.attempts[2].provider").value("splitnow")).andExpect(jsonPath("$.application.status").value("declined"));

        // KreditKo was fourth in line, so it was never asked.
        WORLD.verify(0, postRequestedFor(urlEqualTo("/kreditko/loans")));
    }

    @Test
    void aProviderThatCannotBeReachedIsSkippedToo() throws Exception {
        setRouting("{\"routing_mode\":\"cheapest\"}");
        json(post("/paylatereu/api").withRequestBody(containing("\"action\":\"authorise\"")).atPriority(1), 504, "{}");
        splitNowDeclines();
        json(post("/lendo/api/applications"), 202, "{\"application_id\":\"ld_1\",\"status\":\"processing\"}");

        applyBest(EVERYTHING, true).andExpect(status().isCreated()).andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].status").value("failed")).andExpect(jsonPath("$.attempts[1].provider").value("lendo"));
    }

    @Test
    void aProviderThatAsksForDetailsTheCustomerDidNotGiveIsSkipped() throws Exception {
        setRouting("{\"routing_mode\":\"cheapest\"}");
        payLaterAuthorises("DECLINED");
        json(post("/splitnow/v1/checkouts"), 201, "{\"id\":\"sn_1\",\"state\":\"approved\"}");

        // Lendo is second but needs a birthdate; with only an email the next one that can use it is SplitNow.
        applyBest("{\"email\":\"anna@example.com\"}", true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.attempts.length()").value(2)).andExpect(jsonPath("$.attempts[1].provider").value("splitnow"))
                .andExpect(jsonPath("$.application.status").value("approved"));
        WORLD.verify(0, postRequestedFor(urlEqualTo("/lendo/api/applications")));
    }

    @Test
    void asksForWhatTheBestProviderNeedsWhenNothingFits() throws Exception {
        applyBest("{}", true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("applicant_incomplete"));
        payment(1_500, "requires_payment_method", "automatic");
        applyBest(EVERYTHING, true).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("provider_not_available"));
    }

    // --- learning ----------------------------------------------------------------------------------------------

    @Test
    void declinesLowerAProvidersApprovalRateAndTheRankingFollows() throws Exception {
        setRouting("{\"routing_mode\":\"best_approval\"}");
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended.provider").value("splitnow"));
        splitNowDeclines();

        // Twelve declines against a prior of 85% over ten observations: about 39%, below PayLaterEU's 80%.
        for (int i = 0; i < 12; i++) {
            mvc.perform(MockMvcRequestBuilders.post("/checkout/" + secret + "/bnpl/apply").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"splitnow\",\"plan\":\"splitnow_3\",\"applicant\":{\"email\":\"anna@example.com\"}}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("declined"));
        }

        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended.provider").value("paylatereu"));
        String body = mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/routing_decisions/" + paymentId()).with(merchant()))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.startsWith("Chose PayLaterEU: most likely to approve (80% vs ")))
                .andReturn().getResponse().getContentAsString();
        for (var ranked : new tools.jackson.databind.json.JsonMapper().readTree(body).get("ranked")) {
            if ("splitnow".equals(ranked.get("provider").asString())) {
                assertThat(ranked.get("stats").get("approval_rate").asDouble()).isLessThan(0.45);
            }
        }
    }

    @Test
    void measuredDecisionTimesReplaceTheAssumedOnes() throws Exception {
        setRouting("{\"routing_mode\":\"fastest\"}");
        json(post("/splitnow/v1/checkouts"), 201, "{\"id\":\"sn_1\",\"state\":\"approved\"}");
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/routing_decisions/" + paymentId()).with(merchant()))
                .andExpect(jsonPath("$.reason").value("Chose SplitNow: fastest to decide (about 300 ms vs 600 ms for PayLaterEU)"));

        // Make SplitNow slow in the records, as if it had been taking a minute: the router should stop calling it fastest.
        for (int i = 0; i < 3; i++) {
            mvc.perform(MockMvcRequestBuilders.post("/checkout/" + secret + "/bnpl/apply").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"provider\":\"splitnow\",\"plan\":\"splitnow_3\",\"applicant\":{\"email\":\"anna@example.com\"}}"))
                    .andExpect(status().isCreated());
            jdbc.sql("UPDATE bnpl_applications SET decision_ms = 60000, status = 'declined' WHERE payment_id = :p").param("p", paymentId()).update();
        }

        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended.provider").value("paylatereu"));
    }

    // --- analytics ---------------------------------------------------------------------------------------------

    @Test
    void theMerchantSeesHowEachProviderHasDone() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/analytics").with(merchant())).andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.length()").value(4)).andExpect(jsonPath("$.applications").value(0))
                .andExpect(jsonPath("$.providers[0].approval_rate").doesNotExist());

        splitNowDeclines();
        mvc.perform(MockMvcRequestBuilders.post("/checkout/" + secret + "/bnpl/apply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"splitnow\",\"plan\":\"splitnow_3\",\"applicant\":{\"email\":\"anna@example.com\"}}")).andExpect(status().isCreated());
        json(post("/splitnow/v1/checkouts"), 201, "{\"id\":\"sn_2\",\"state\":\"approved\"}");
        mvc.perform(MockMvcRequestBuilders.post("/checkout/" + secret + "/bnpl/apply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"splitnow\",\"plan\":\"splitnow_4\",\"applicant\":{\"email\":\"anna@example.com\"}}")).andExpect(status().isCreated());
        json(post("/lendo/api/applications"), 202, "{\"application_id\":\"ld_9\",\"status\":\"processing\"}");
        String other = "pay_" + java.util.UUID.randomUUID().toString().replace("-", "") + "_secret_x";
        payment(other, 30_000, "requires_payment_method", "automatic");
        mvc.perform(MockMvcRequestBuilders.post("/checkout/" + other + "/bnpl/apply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"lendo\",\"plan\":\"lendo_12\",\"applicant\":{\"email\":\"a@example.com\",\"birthdate\":\"1990-01-15\"}}")).andExpect(status().isCreated());

        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/analytics").with(merchant()).param("days", "7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7)).andExpect(jsonPath("$.applications").value(3))
                .andExpect(jsonPath("$.approved").value(1)).andExpect(jsonPath("$.volume").value(30_000)).andExpect(jsonPath("$.fees").value(1_050))
                .andExpect(jsonPath("$.providers[0].provider").value("splitnow")).andExpect(jsonPath("$.providers[0].applications").value(2))
                .andExpect(jsonPath("$.providers[0].approved").value(1)).andExpect(jsonPath("$.providers[0].declined").value(1))
                .andExpect(jsonPath("$.providers[0].approval_rate").value(0.5)).andExpect(jsonPath("$.providers[0].circuit").value("closed"))
                .andExpect(jsonPath("$.providers[1].provider").value("lendo")).andExpect(jsonPath("$.providers[1].pending").value(1))
                .andExpect(jsonPath("$.providers[1].approval_rate").doesNotExist());
        // Another merchant sees none of it.
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/analytics").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.claim("mid", "mer_other"))))
                .andExpect(jsonPath("$.applications").value(0));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/analytics")).andExpect(status().isUnauthorized());
    }
}
