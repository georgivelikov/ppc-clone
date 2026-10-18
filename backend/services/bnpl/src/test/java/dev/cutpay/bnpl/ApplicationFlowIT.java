package dev.cutpay.bnpl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cutpay.bnpl.application.ApplicationService;
import dev.cutpay.web.SimulatedClock;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applying for instalments against the four providers: an instant answer, an answer by signed callback, an answer that
 * has to be asked for, a provider that fails, and everything that must not be possible.
 */
class ApplicationFlowIT extends BnplIntegrationTest {

    @Autowired
    JsonMapper json;

    @Autowired
    ApplicationService service;

    @Autowired
    Clock clock;

    @Autowired
    KafkaTemplate<String, String> kafka;

    private static final String ANNA = "{\"email\":\"anna@example.com\",\"name\":\"Anna Petrova\"}";

    private ResultActions apply(String secretToUse, String provider, String plan, String applicant) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/checkout/" + secretToUse + "/bnpl/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"%s\",\"plan\":\"%s\",\"applicant\":%s}".formatted(provider, plan, applicant)));
    }

    private ResultActions apply(String provider, String plan, String applicant) throws Exception {
        return apply(secret, provider, plan, applicant);
    }

    private String idOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private ResultActions merchantView(String id) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/applications/" + id).with(merchant()));
    }

    private ResultActions customerView(String id) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/applications/" + id));
    }

    private static void splitNowAnswers(String state, String declineReason) {
        json(post("/splitnow/v1/checkouts"), 201, """
                {"id":"sn_1","state":"%s","decline_reason":%s,"amount_cents":30000,"currency":"EUR","instalments":3,
                 "merchant_fee_cents":1050,"schedule":[],"reference":"x","refunded_cents":0}"""
                .formatted(state, declineReason == null ? "null" : "\"" + declineReason + "\""));
    }

    private static void lendoProcesses() {
        json(post("/lendo/api/applications"), 202, "{\"application_id\":\"ld_1\",\"status\":\"processing\"}");
    }

    private static void kreditkoReviews() {
        json(post("/kreditko/loans"), 201, "{\"loanId\":\"KK-1\",\"status\":\"UNDER_REVIEW\",\"amountMinor\":30000}");
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("lendo_test_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions lendoCallback(String body, String signature) throws Exception {
        var request = MockMvcRequestBuilders.post("/bnpl/callback/lendo").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body);
        if (signature != null) {
            request.header("X-Lendo-Signature", signature);
        }
        return mvc.perform(request);
    }

    private static final String LENDO_APPLICANT = "{\"email\":\"anna@example.com\",\"birthdate\":\"1990-01-15\"}";
    private static final String KREDITKO_APPLICANT = "{\"email\":\"anna@example.com\",\"national_id\":\"AB123456\",\"monthly_income\":300000}";

    // --- an instant answer -------------------------------------------------------------------------------------

    @Test
    void splitNowApprovesAtOnceAndTheMerchantSeesWhatItCostsWhileTheCustomerDoesNot() throws Exception {
        splitNowAnswers("approved", null);

        ResultActions applied = apply("splitnow", "splitnow_3", ANNA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("approved")).andExpect(jsonPath("$.provider_name").value("SplitNow"))
                .andExpect(jsonPath("$.instalment_amounts[0]").value(10000)).andExpect(jsonPath("$.total_payable").value(30000))
                .andExpect(jsonPath("$.awaiting_decision").value(false))
                .andExpect(jsonPath("$.message").value("Your application was approved."));
        String id = idOf(applied);
        assertThat(applied.andReturn().getResponse().getContentAsString()).doesNotContain("merchant_fee");

        merchantView(id).andExpect(jsonPath("$.status").value("approved")).andExpect(jsonPath("$.merchant_fee").value(1050))
                .andExpect(jsonPath("$.net_amount").value(28950)).andExpect(jsonPath("$.provider_ref").value("sn_1"))
                .andExpect(jsonPath("$.payment").value(paymentId())).andExpect(jsonPath("$.customer_email").value("anna@example.com"))
                .andExpect(jsonPath("$.decision_ms").isNumber());
        assertThat(events(id)).containsExactly("BnplApplicationCreated", "BnplApproved");
        WORLD.verify(postRequestedFor(urlEqualTo("/splitnow/v1/checkouts")).withRequestBody(containing("\"reference\":\"" + id + "\""))
                .withRequestBody(containing("\"instalments\":3")).withRequestBody(containing("anna@example.com")));
    }

    @Test
    void aDeclinedApplicationLeavesTheCustomerFreeToTryAnotherProvider() throws Exception {
        splitNowAnswers("declined", "risk_check_failed");

        String declined = idOf(apply("splitnow", "splitnow_3", ANNA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("declined")).andExpect(jsonPath("$.message").value(
                        "The provider could not approve this application. You can choose another option.")));
        merchantView(declined).andExpect(jsonPath("$.decline_reason").value("risk_check_failed"));
        assertThat(events(declined)).containsExactly("BnplApplicationCreated", "BnplDeclined");

        json(post("/paylatereu/api").withRequestBody(containing("\"action\":\"authorise\"")).atPriority(1),
                "{\"ok\":true,\"code\":\"OK\",\"data\":{\"authId\":\"PL1\",\"state\":\"AUTHORISED\",\"amount\":300.00}}");
        apply("paylatereu", "paylatereu_30", ANNA).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("approved"));
    }

    @Test
    void whatTheProviderNeedsToKnowIsAskedForBeforeAnythingIsSent() throws Exception {
        apply("kreditko", "kreditko_12", ANNA).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("applicant_incomplete"))
                .andExpect(jsonPath("$.error.message").value("Missing: national_id, monthly_income"));
        apply("lendo", "lendo_12", ANNA).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Missing: birthdate"));
        apply("splitnow", "splitnow_3", "{\"email\":\"not-an-email\"}").andExpect(status().isBadRequest());
        apply("splitnow", "splitnow_3", "null").andExpect(status().isBadRequest());
        WORLD.verify(0, postRequestedFor(urlEqualTo("/kreditko/loans")));
        WORLD.verify(0, postRequestedFor(urlEqualTo("/splitnow/v1/checkouts")));
    }

    // --- an answer by callback ---------------------------------------------------------------------------------

    @Test
    void lendoDecidesLaterAndItsSignedCallbackApprovesTheApplicationOnce() throws Exception {
        lendoProcesses();
        String id = idOf(apply("lendo", "lendo_12", LENDO_APPLICANT).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending_review")).andExpect(jsonPath("$.awaiting_decision").value(true)));
        WORLD.verify(postRequestedFor(urlEqualTo("/lendo/api/applications"))
                .withRequestBody(containing("reference=" + id)).withRequestBody(containing("months=12"))
                .withRequestBody(containing("birthdate=1990-01-15")).withRequestBody(containing("amount=300.00"))
                .withRequestBody(containing("callback_url=")));
        assertThat(events(id)).containsExactly("BnplApplicationCreated");

        String body = "application_id=ld_1&outcome=accepted&score=700&reference=" + id;
        lendoCallback(body, sign(body)).andExpect(status().isNoContent());
        customerView(id).andExpect(jsonPath("$.status").value("approved"));
        merchantView(id).andExpect(jsonPath("$.provider_ref").value("ld_1")).andExpect(jsonPath("$.merchant_fee").value(870));

        // The provider may send it twice; the second changes nothing.
        lendoCallback(body, sign(body)).andExpect(status().isNoContent());
        assertThat(events(id)).containsExactly("BnplApplicationCreated", "BnplApproved");
    }

    @Test
    void aCallbackThatIsNotSignedByLendoIsRefusedAndChangesNothing() throws Exception {
        lendoProcesses();
        String id = idOf(apply("lendo", "lendo_12", LENDO_APPLICANT));
        String body = "application_id=ld_1&outcome=accepted&score=700&reference=" + id;

        lendoCallback(body, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("invalid_signature"));
        lendoCallback(body, "deadbeef").andExpect(status().isUnauthorized());
        // A real signature of a different body does not vouch for this one.
        lendoCallback(body, sign(body + "&extra=1")).andExpect(status().isUnauthorized());
        customerView(id).andExpect(jsonPath("$.status").value("pending_review"));

        String unknown = "application_id=ld_9&outcome=accepted&score=700&reference=bnpl_nope";
        lendoCallback(unknown, sign(unknown)).andExpect(status().isNotFound());
        String rejected = "application_id=ld_1&outcome=rejected&score=400&reference=" + id;
        lendoCallback(rejected, sign(rejected)).andExpect(status().isNoContent());
        customerView(id).andExpect(jsonPath("$.status").value("declined"));
        merchantView(id).andExpect(jsonPath("$.decline_reason").value("credit_check_failed"));
        mvc.perform(MockMvcRequestBuilders.post("/bnpl/callback/splitnow").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("a=b"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aLateApprovalAfterTheCustomerGaveUpDoesNotBringTheApplicationBack() throws Exception {
        lendoProcesses();
        json(post("/lendo/api/applications/ld_1/cancel"), "{\"application_id\":\"ld_1\",\"status\":\"canceled\"}");
        String id = idOf(apply("lendo", "lendo_12", LENDO_APPLICANT));

        mvc.perform(MockMvcRequestBuilders.post("/checkout/" + secret + "/bnpl/applications/" + id + "/cancel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("canceled"));
        WORLD.verify(postRequestedFor(urlEqualTo("/lendo/api/applications/ld_1/cancel")));

        String body = "application_id=ld_1&outcome=accepted&score=700&reference=" + id;
        lendoCallback(body, sign(body)).andExpect(status().isNoContent());
        customerView(id).andExpect(jsonPath("$.status").value("canceled"));
        assertThat(events(id)).containsExactly("BnplApplicationCreated", "BnplCanceled");
        mvc.perform(MockMvcRequestBuilders.post("/checkout/" + secret + "/bnpl/applications/" + id + "/cancel"))
                .andExpect(status().isConflict());
    }

    // --- an answer that has to be asked for --------------------------------------------------------------------

    @Test
    void kreditKoNeverCallsBackSoUnderReviewApplicationsAreAskedAboutUntilDecided() throws Exception {
        kreditkoReviews();
        String id = idOf(apply("kreditko", "kreditko_12", KREDITKO_APPLICANT).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending_review")));
        WORLD.verify(postRequestedFor(urlEqualTo("/kreditko/loans")).withRequestBody(containing("\"nationalId\":\"AB123456\""))
                .withRequestBody(containing("\"monthlyIncomeMinor\":300000")).withRequestBody(containing("\"termMonths\":12")));

        // Still under review: asking changes nothing.
        json(get(urlEqualTo("/kreditko/loans/KK-1")), "{\"loanId\":\"KK-1\",\"status\":\"UNDER_REVIEW\"}");
        service.pollWaiting(100);
        customerView(id).andExpect(jsonPath("$.status").value("pending_review"));

        json(get(urlEqualTo("/kreditko/loans/KK-1")), "{\"loanId\":\"KK-1\",\"status\":\"APPROVED\",\"feeMinor\":1350}");
        service.pollWaiting(100);
        customerView(id).andExpect(jsonPath("$.status").value("approved"));
        assertThat(events(id)).containsExactly("BnplApplicationCreated", "BnplApproved");
    }

    @Test
    void kreditKoRejectionFoundByAskingDeclinesTheApplication() throws Exception {
        kreditkoReviews();
        String id = idOf(apply("kreditko", "kreditko_12", KREDITKO_APPLICANT));
        json(get(urlEqualTo("/kreditko/loans/KK-1")), "{\"loanId\":\"KK-1\",\"status\":\"REJECTED\",\"reason\":\"insufficient_income\"}");

        service.pollWaiting(100);

        merchantView(id).andExpect(jsonPath("$.status").value("declined")).andExpect(jsonPath("$.decline_reason").value("insufficient_income"));
    }

    @Test
    void anApplicationNobodyDecidesInADayExpiresAndTheProviderIsToldToForgetIt() throws Exception {
        kreditkoReviews();
        json(post("/kreditko/loans/KK-1/cancel"), "{\"loanId\":\"KK-1\",\"status\":\"CANCELED\"}");
        String id = idOf(apply("kreditko", "kreditko_12", KREDITKO_APPLICANT));

        service.expireStale(100);
        customerView(id).andExpect(jsonPath("$.status").value("pending_review"));

        ((SimulatedClock) clock).advance(Duration.ofHours(25));
        service.expireStale(100);

        customerView(id).andExpect(jsonPath("$.status").value("expired")).andExpect(jsonPath("$.awaiting_decision").value(false));
        merchantView(id).andExpect(jsonPath("$.decline_reason").value("expired"));
        assertThat(events(id)).containsExactly("BnplApplicationCreated", "BnplExpired");
        WORLD.verify(postRequestedFor(urlEqualTo("/kreditko/loans/KK-1/cancel")));
    }

    // --- a provider that fails ---------------------------------------------------------------------------------

    @Test
    void aProviderThatTimesOutFailsTheApplicationWithoutDecliningTheCustomerAndAnotherCanBeTried() throws Exception {
        json(post("/splitnow/v1/checkouts"), 504, "{}");

        apply("splitnow", "splitnow_3", ANNA).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("provider_unavailable"))
                .andExpect(jsonPath("$.error.message").value("SplitNow could not take the application. You can try another option."));
        String failed = jdbc.sql("SELECT id FROM bnpl_applications WHERE payment_id = :p").param("p", paymentId()).query(String.class).single();
        merchantView(failed).andExpect(jsonPath("$.status").value("failed")).andExpect(jsonPath("$.decline_reason").value("provider_timeout"));
        assertThat(events(failed)).containsExactly("BnplApplicationCreated", "BnplProviderTimedOut");

        splitNowAnswers("approved", null);
        apply("splitnow", "splitnow_4", ANNA).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("approved"));
    }

    @Test
    void aProviderThatIsDownOrRefusesIsToldApart() throws Exception {
        json(post("/splitnow/v1/checkouts"), 503, "{}");
        apply("splitnow", "splitnow_3", ANNA).andExpect(status().isBadGateway());
        assertThat(jdbc.sql("SELECT e.event_type FROM outbox e JOIN bnpl_applications a ON a.id = e.event_key WHERE a.payment_id = :p ORDER BY e.id")
                .param("p", paymentId()).query(String.class).list())
                .containsExactly("BnplApplicationCreated", "BnplProviderFailed");

        json(post("/splitnow/v1/checkouts"), 422, "{\"error\":{\"type\":\"unsupported_plan\"}}");
        apply("splitnow", "splitnow_3", ANNA).andExpect(status().isBadGateway());
        assertThat(jdbc.sql("SELECT status || ':' || decline_reason FROM bnpl_applications WHERE payment_id = :p ORDER BY created_at")
                .param("p", paymentId()).query(String.class).list())
                .containsExactly("failed:provider_unavailable", "declined:refused_by_provider");
    }

    // --- what must not be possible ----------------------------------------------------------------------------

    @Test
    void thereIsOnlyOneLiveApplicationPerPayment() throws Exception {
        lendoProcesses();
        apply("lendo", "lendo_12", LENDO_APPLICANT).andExpect(status().isCreated());

        splitNowAnswers("approved", null);
        apply("splitnow", "splitnow_3", ANNA).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("application_in_progress"));
        WORLD.verify(0, postRequestedFor(urlEqualTo("/splitnow/v1/checkouts")));
    }

    @Test
    void anOfferThatIsGoneOrNotAllowedCannotBeApplied() throws Exception {
        apply("splitnow", "splitnow_9", ANNA).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("offer_not_available"));
        apply("klarna", "klarna_3", ANNA).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("unknown_provider"));

        payment(5_000, "requires_payment_method", "automatic");
        apply("lendo", "lendo_12", LENDO_APPLICANT).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("provider_not_available"));

        mvc.perform(MockMvcRequestBuilders.put("/v1/bnpl/settings").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"providers\":[{\"code\":\"splitnow\",\"enabled\":false}]}")).andExpect(status().isOk());
        apply("splitnow", "splitnow_3", ANNA).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("provider_not_available"));

        payment(30_000, "succeeded", "automatic");
        apply("paylatereu", "paylatereu_30", ANNA).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("payment_not_payable"));
    }

    @Test
    void anApplicationBelongsToItsPaymentAndItsMerchant() throws Exception {
        splitNowAnswers("approved", null);
        String id = idOf(apply("splitnow", "splitnow_3", ANNA));

        String otherSecret = "pay_" + UUID.randomUUID().toString().replace("-", "") + "_secret_x";
        payment(otherSecret, 30_000, "requires_payment_method", "automatic");
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + otherSecret + "/bnpl/applications/" + id)).andExpect(status().isNotFound());

        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/applications/" + id).with(jwt().jwt(j -> j.claim("mid", "mer_other"))))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/applications/" + id).with(merchant()).param("livemode", "true"))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/applications/" + id)).andExpect(status().isUnauthorized());
        // An approved application is not the customer's to cancel any more.
        mvc.perform(MockMvcRequestBuilders.post("/checkout/" + secret + "/bnpl/applications/" + id + "/cancel")).andExpect(status().isConflict());
    }

    @Test
    void theMerchantListsItsApplicationsWithFilters() throws Exception {
        splitNowAnswers("declined", "risk_check_failed");
        String declined = idOf(apply("splitnow", "splitnow_3", ANNA));
        splitNowAnswers("approved", null);
        String approved = idOf(apply("splitnow", "splitnow_4", ANNA));

        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/applications").with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].id").value(approved))
                .andExpect(jsonPath("$.has_more").value(false));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/applications").with(merchant()).param("status", "declined"))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(declined));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/applications").with(merchant()).param("provider", "lendo"))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/applications").with(merchant()).param("payment", paymentId()).param("limit", "1"))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.has_more").value(true));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/applications").with(jwt().jwt(j -> j.claim("mid", "mer_other"))))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- the payment was paid another way ---------------------------------------------------------------------

    @Test
    void whenPaymentsSaysThePaymentWasPaidAnotherWayTheApplicationIsClosedAtTheProvider() throws Exception {
        lendoProcesses();
        json(post("/lendo/api/applications/ld_1/cancel"), "{\"application_id\":\"ld_1\",\"status\":\"canceled\"}");
        String id = idOf(apply("lendo", "lendo_12", LENDO_APPLICANT));
        String body = "application_id=ld_1&outcome=accepted&score=700&reference=" + id;
        lendoCallback(body, sign(body)).andExpect(status().isNoContent());
        customerView(id).andExpect(jsonPath("$.status").value("approved"));

        kafka.send("payment.events", paymentId(), """
                {"event_id":"evt_%s","event_type":"BnplPaymentRejected","occurred_at":"2026-10-19T10:00:00Z",
                 "aggregate_id":"%s","merchant_id":"%s","livemode":false,"version":1,
                 "payload":{"application_id":"%s","payment_id":"%s","reason":"payment_not_payable"}}"""
                .formatted(UUID.randomUUID(), paymentId(), merchantId, id, paymentId()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> merchantView(id).andExpect(jsonPath("$.status").value("canceled")));
        merchantView(id).andExpect(jsonPath("$.decline_reason").value("payment_settled_elsewhere"));
        assertThat(events(id)).containsExactly("BnplApplicationCreated", "BnplApproved", "BnplCanceled");
        WORLD.verify(postRequestedFor(urlEqualTo("/lendo/api/applications/ld_1/cancel")));
    }
}
