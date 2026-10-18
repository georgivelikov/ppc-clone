package dev.cutpay.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"cutpay.acquirer.read-timeout=PT1S", "cutpay.outbox.poll-interval=PT0.2S"})
@AutoConfigureMockMvc
@Testcontainers
class PaymentsFlowIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static final WireMockServer ACQUIRER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        ACQUIRER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("cutpay.acquirer.url", ACQUIRER::baseUrl);
    }

    @AfterAll
    static void stopAcquirer() {
        ACQUIRER.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    private String merchantId;

    @BeforeEach
    void freshMerchantAndStubs() {
        merchantId = "mer_" + UUID.randomUUID().toString().replace("-", "");
        ACQUIRER.resetAll();
        ACQUIRER.stubFor(WireMock.get(urlEqualTo("/tokens/tok_visa")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"token":"tok_visa","brand":"visa","last4":"4242","exp_month":12,"exp_year":2099,
                         "fingerprint":"fp_1"}""")));
    }

    private RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId).claim("role", "owner"));
    }

    private void stubAuthorize(String body) {
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/authorize")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static final String PAYMENT = """
            {"amount":2500,"currency":"EUR","description":"Flat white x2","merchant_name":"Rose Cafe"}""";

    private String createPayment() throws Exception {
        String body = mvc.perform(post("/v1/payment_intents").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("requires_payment_method"))
                .andReturn().getResponse().getContentAsString();
        return body;
    }

    private String field(String body, String name) {
        return json.readTree(body).get(name).asString();
    }

    private void confirm(String secret) throws Exception {
        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"tok_visa\",\"email\":\"anna@example.com\"}"));
    }

    @Test
    void payingWithAnApprovedCardSucceedsAndStoresOnlySafeCardData() throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_1"}""");
        String payment = createPayment();
        String secret = field(payment, "client_secret");

        mvc.perform(get("/checkout/sessions/%s".formatted(secret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant_name").value("Rose Cafe"))
                .andExpect(jsonPath("$.amount").value(2500))
                .andExpect(jsonPath("$.client_secret").doesNotExist());

        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok_visa\",\"email\":\"anna@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"));

        mvc.perform(get("/v1/payment_intents/" + field(payment, "id")).with(merchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.customer_email").value("anna@example.com"))
                .andExpect(jsonPath("$.attempts[0].card_last4").value("4242"))
                .andExpect(jsonPath("$.attempts[0].card_brand").value("visa"));

        // The event is written in the same transaction and later relayed to Kafka.
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE event_key = :id AND topic = 'payment.events'")
                .param("id", field(payment, "id")).query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void latestAttemptIsAnIdUnlessExpandedAndListsAreConsistent() throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_1"}""");
        String payment = createPayment();
        String id = field(payment, "id");
        mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.latest_attempt").doesNotExist()); // nothing tried yet
        confirm(field(payment, "client_secret"));

        mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.latest_attempt").value(org.hamcrest.Matchers.startsWith("att_")));
        mvc.perform(get("/v1/payment_intents/" + id).param("expand[]", "latest_attempt").with(merchant()))
                .andExpect(jsonPath("$.latest_attempt.card_last4").value("4242"))
                .andExpect(jsonPath("$.latest_attempt.status").value("succeeded"));
        mvc.perform(get("/v1/payment_intents").param("expand[]", "latest_attempt").with(merchant()))
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.data[0].latest_attempt.card_brand").value("visa"));
        // Unknown expansions are ignored rather than rejected, so new ones can be added without breaking callers.
        mvc.perform(get("/v1/payment_intents/" + id).param("expand[]", "nothing").with(merchant()))
                .andExpect(status().isOk());
    }

    @Test
    void refundListsAreObjectsWithPaging() throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_1"}""");
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/refund")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"status":"succeeded","refund_ref":"ref_1"}""")));
        String payment = createPayment();
        confirm(field(payment, "client_secret"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/v1/refunds").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"payment_intent\":\"" + field(payment, "id") + "\",\"amount\":500}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.object").value("refund"));
        }
        mvc.perform(get("/v1/refunds").param("limit", "1").with(merchant()))
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.has_more").value(true));
        mvc.perform(get("/v1/refunds").param("limit", "5").with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.has_more").value(false));
    }

    @Test
    void aDeclineEndsInFailedWithAReadableMessage() throws Exception {
        stubAuthorize("""
                {"status":"declined","decline_code":"card_declined","message":"Your card was declined."}""");
        String secret = field(createPayment(), "client_secret");

        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok_visa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure_code").value("card_declined"))
                .andExpect(jsonPath("$.failure_message").value("Your card was declined."));
    }

    @Test
    void anAcquirerOutageFailsThePaymentInsteadOfLeavingItStuck() throws Exception {
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/authorize")).willReturn(aResponse().withStatus(502)));
        String secret = field(createPayment(), "client_secret");

        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok_visa\"}"))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure_code").value("processing_error"));
    }

    @Test
    void aSlowAcquirerTimesOut() throws Exception {
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/authorize")).willReturn(aResponse().withFixedDelay(3000)
                .withHeader("Content-Type", "application/json").withBody("{\"status\":\"approved\"}")));
        String secret = field(createPayment(), "client_secret");

        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok_visa\"}"))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure_code").value("processing_error"));
    }

    @Test
    void threeDsPausesThePaymentUntilTheAcquirerCallsBack() throws Exception {
        stubAuthorize("""
                {"status":"requires_action","acquirer_ref":"auth_3ds"}""");
        String secret = field(createPayment(), "client_secret");

        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok_visa\"}"))
                .andExpect(jsonPath("$.status").value("requires_action"))
                .andExpect(jsonPath("$.next_action.type").value("three_ds"))
                .andExpect(jsonPath("$.next_action.challenge_id").value("auth_3ds"));

        mvc.perform(post("/internal/3ds/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acquirer_ref\":\"auth_3ds\",\"status\":\"approved\"}"))
                .andExpect(status().isNoContent());
        // A repeated callback must not break anything.
        mvc.perform(post("/internal/3ds/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acquirer_ref\":\"auth_3ds\",\"status\":\"approved\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/checkout/sessions/%s".formatted(secret)))
                .andExpect(jsonPath("$.status").value("succeeded"));
    }

    @Test
    void aFailedThreeDsChallengeFailsThePayment() throws Exception {
        stubAuthorize("""
                {"status":"requires_action","acquirer_ref":"auth_bad"}""");
        String secret = field(createPayment(), "client_secret");
        confirm(secret);

        mvc.perform(post("/internal/3ds/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acquirer_ref\":\"auth_bad\",\"status\":\"declined\","
                                + "\"decline_code\":\"authentication_failed\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/checkout/sessions/%s".formatted(secret)))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure_code").value("authentication_failed"));
    }

    @Test
    void aPaidPaymentCannotBePaidTwice() throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_1"}""");
        String secret = field(createPayment(), "client_secret");
        confirm(secret);

        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok_visa\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("payment_not_confirmable"));
    }

    @Test
    void theSameIdempotencyKeyCreatesOnePayment() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(post("/v1/payment_intents").with(merchant()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/v1/payment_intents").with(merchant()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(field(first, "id")));

        assertThat(jdbc.sql("SELECT count(*) FROM payment_intents WHERE merchant_id = :m")
                .param("m", merchantId).query(Long.class).single()).isEqualTo(1L);

        // Same key, different request: a client bug that must not be papered over.
        mvc.perform(post("/v1/payment_intents").with(merchant()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":9999,\"currency\":\"EUR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_reuse"));
    }

    @Test
    void idempotencyKeysAreScopedPerMerchant() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/v1/payment_intents").with(merchant()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/payment_intents")
                        .with(jwt().jwt(j -> j.claim("mid", "mer_other").claim("role", "owner")))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void merchantsCannotSeeEachOthersPayments() throws Exception {
        String id = field(createPayment(), "id");
        mvc.perform(get("/v1/payment_intents/" + id)
                        .with(jwt().jwt(j -> j.claim("mid", "mer_other").claim("role", "owner"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/payment_intents")).andExpect(status().isUnauthorized());
    }

    private RequestPostProcessor apiKey(boolean livemode) {
        return jwt().jwt(j -> j.claim("mid", merchantId).claim("role", "api").claim("livemode", livemode));
    }

    @Test
    void testAndLiveDataNeverMix() throws Exception {
        String testPayment = field(mvc.perform(post("/v1/payment_intents").with(apiKey(false))
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.livemode").value(false))
                .andReturn().getResponse().getContentAsString(), "id");
        String livePayment = field(mvc.perform(post("/v1/payment_intents").with(apiKey(true))
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.livemode").value(true))
                .andReturn().getResponse().getContentAsString(), "id");

        // Lists are separate...
        mvc.perform(get("/v1/payment_intents").with(apiKey(false)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(testPayment));
        mvc.perform(get("/v1/payment_intents").with(apiKey(true)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(livePayment));

        // ...and fetching or changing the other mode's object by id looks like it does not exist.
        mvc.perform(get("/v1/payment_intents/" + livePayment).with(apiKey(false))).andExpect(status().isNotFound());
        mvc.perform(get("/v1/payment_intents/" + testPayment).with(apiKey(true))).andExpect(status().isNotFound());
        mvc.perform(post("/v1/payment_intents/" + livePayment + "/cancel").with(apiKey(false)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/refunds").with(apiKey(true)).param("payment_intent", testPayment))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(post("/v1/refunds").with(apiKey(true)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_intent\":\"" + testPayment + "\"}"))
                .andExpect(status().isNotFound());

        // A dashboard session has no mode and works on test data.
        mvc.perform(get("/v1/payment_intents").with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(testPayment));
    }

    @Test
    void aDashboardSessionChoosesTheModeWithTheQueryAndCanOpenEitherByIdButAnApiKeyCannot() throws Exception {
        String testPayment = field(mvc.perform(post("/v1/payment_intents").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.livemode").value(false))
                .andReturn().getResponse().getContentAsString(), "id");
        String livePayment = field(mvc.perform(post("/v1/payment_intents").param("livemode", "true").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.livemode").value(true))
                .andReturn().getResponse().getContentAsString(), "id");

        mvc.perform(get("/v1/payment_intents").with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(testPayment));
        mvc.perform(get("/v1/payment_intents").param("livemode", "true").with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(livePayment));
        mvc.perform(get("/v1/payment_stats").param("livemode", "true").with(merchant())).andExpect(status().isOk());

        // The detail page does not know the mode of the payment it is opening.
        mvc.perform(get("/v1/payment_intents/" + livePayment).with(merchant())).andExpect(status().isOk());
        mvc.perform(get("/v1/payment_intents/" + testPayment).with(merchant())).andExpect(status().isOk());
        // An API key is held to its own mode, even if it asks for the other one.
        mvc.perform(get("/v1/payment_intents").param("livemode", "true").with(apiKey(false)))
                .andExpect(jsonPath("$.data[0].id").value(testPayment));
        mvc.perform(get("/v1/payment_intents/" + livePayment).with(apiKey(false))).andExpect(status().isNotFound());
    }

    @Test
    void idempotencyKeysAreScopedPerMode() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/v1/payment_intents").with(apiKey(false)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.livemode").value(false));
        mvc.perform(post("/v1/payment_intents").with(apiKey(true)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.livemode").value(true));
    }

    @Test
    void twoHalfRefundsRefundThePaymentAndAThirdIsRejected() throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_1"}""");
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/refund")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"succeeded\",\"refund_ref\":\"ref_1\"}")));
        String payment = createPayment();
        String id = field(payment, "id");
        confirm(field(payment, "client_secret"));

        refund(id, 1250).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("succeeded"));
        mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.status").value("partially_refunded"))
                .andExpect(jsonPath("$.amount_refunded").value(1250));

        refund(id, 1250).andExpect(status().isCreated());
        mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.status").value("refunded"))
                .andExpect(jsonPath("$.refunds.length()").value(2));

        refund(id, 1).andExpect(status().isBadRequest());
    }

    @Test
    void aRefundLargerThanTheRemainingAmountIsRejected() throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_1"}""");
        String payment = createPayment();
        confirm(field(payment, "client_secret"));

        refund(field(payment, "id"), 2501).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("refund_exceeds_charge"));
    }

    @Test
    void aFailedRefundReleasesTheReservedAmount() throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_1"}""");
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/refund")).willReturn(aResponse().withStatus(500)));
        String payment = createPayment();
        String id = field(payment, "id");
        confirm(field(payment, "client_secret"));

        refund(id, 1000).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("failed"));
        mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.amount_refunded").value(0));
    }

    @Test
    void anUnpaidPaymentCannotBeRefunded() throws Exception {
        String id = field(createPayment(), "id");
        refund(id, 100).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("payment_not_refundable"));
    }

    @Test
    void listFiltersByStatusAndPaginatesWithACursor() throws Exception {
        for (int i = 0; i < 3; i++) {
            createPayment();
        }
        String page = mvc.perform(get("/v1/payment_intents").param("limit", "2").with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.has_more").value(true))
                .andReturn().getResponse().getContentAsString();
        String last = json.readTree(page).get("data").get(1).get("id").asString();

        mvc.perform(get("/v1/payment_intents").param("limit", "2").param("starting_after", last).with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.has_more").value(false));

        mvc.perform(get("/v1/payment_intents").param("status", "succeeded").with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void cancelingAnUnpaidPaymentWorksOnce() throws Exception {
        String id = field(createPayment(), "id");
        mvc.perform(post("/v1/payment_intents/%s/cancel".formatted(id)).with(merchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("canceled"));
        mvc.perform(post("/v1/payment_intents/%s/cancel".formatted(id)).with(merchant()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("invalid_state"));
    }

    private org.springframework.test.web.servlet.ResultActions refund(String paymentId, long amount) throws Exception {
        return mvc.perform(post("/v1/refunds").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_intent\":\"%s\",\"amount\":%d}".formatted(paymentId, amount)));
    }

    // --- phase 4: terminal, links and live status ------------------------------------------------------

    @Test
    void aTerminalChargeIsAMotoPaymentAndTheAcquirerIsToldSoItCanSkip3ds() throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_moto"}""");
        String body = mvc.perform(post("/v1/terminal/charges").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":4200,\"currency\":\"EUR\",\"description\":\"Phone order\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("moto"))
                .andExpect(jsonPath("$.status").value("requires_payment_method"))
                .andReturn().getResponse().getContentAsString();
        String secret = field(body, "client_secret");

        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok_visa\"}"))
                .andExpect(jsonPath("$.status").value("succeeded"));

        ACQUIRER.verify(WireMock.postRequestedFor(urlEqualTo("/authorize"))
                .withRequestBody(WireMock.matchingJsonPath("$.channel", WireMock.equalTo("moto"))));
        mvc.perform(get("/v1/payment_intents/" + field(body, "id")).with(merchant()))
                .andExpect(jsonPath("$.channel").value("moto"));
    }

    @Test
    void anOrdinaryPaymentIsOnlineAndTheAcquirerIsToldThatToo() throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_online"}""");
        String secret = field(createPayment(), "client_secret");
        confirm(secret);
        ACQUIRER.verify(WireMock.postRequestedFor(urlEqualTo("/authorize"))
                .withRequestBody(WireMock.matchingJsonPath("$.channel", WireMock.equalTo("online"))));
    }

    @Test
    void theLinksServiceCanStartAPaymentForAMerchantAndItsMetadataTravelsOnTheEvent() throws Exception {
        String body = mvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant_id":"%s","livemode":false,"merchant_name":"Rose Cafe",
                                 "payment":{"amount":1500,"currency":"EUR","description":"Table 4",
                                            "metadata":{"link_id":"plink_1"},"return_url":"http://x/y"}}"""
                                .formatted(merchantId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_secret").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = field(body, "payment_id");
        assertThat(field(body, "url")).endsWith("/c/" + field(body, "client_secret"));

        mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.metadata.link_id").value("plink_1"))
                .andExpect(jsonPath("$.channel").value("online"));
        mvc.perform(get("/checkout/sessions/" + field(body, "client_secret")))
                .andExpect(jsonPath("$.merchant_name").value("Rose Cafe"));
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE event_key = :id AND event_type = 'PaymentIntentCreated'")
                .param("id", id).query(String.class).single();
        assertThat(json.readTree(payload).get("payload").get("metadata").get("link_id").asString()).isEqualTo("plink_1");
    }

    @Test
    void aStreamStartsWithTheCurrentStatusAndFollowsTheEnd() throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_stream"}""");
        String payment = createPayment();
        String id = field(payment, "id");
        String secret = field(payment, "client_secret");

        var result = mvc.perform(get("/v1/payment_intents/" + id + "/stream").with(merchant())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        confirm(secret);

        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            String sse = result.getResponse().getContentAsString();
            assertThat(sse).contains("event:status").contains("requires_payment_method").contains("succeeded");
        });
    }

    @Test
    void aStreamCannotBeOpenedOnSomeoneElsesPayment() throws Exception {
        String id = field(createPayment(), "id");
        String other = "mer_" + UUID.randomUUID().toString().replace("-", "");
        mvc.perform(get("/v1/payment_intents/" + id + "/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(j -> j.claim("mid", other))))
                .andExpect(status().isNotFound());
    }

    // --- phase 5: pre-authorisation and capture ---------------------------------------------------------

    @Autowired
    java.time.Clock clock;

    @Autowired
    dev.cutpay.payments.payment.PaymentService paymentService;

    private void stubHold(String path, int status) {
        ACQUIRER.stubFor(WireMock.post(urlEqualTo(path)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"approved\",\"acquirer_ref\":\"auth_hold\"}")));
    }

    /** Creates and confirms a payment with capture_method = manual, so it ends up authorised and held. */
    private String authorisedPayment(long amount) throws Exception {
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_hold"}""");
        String body = mvc.perform(post("/v1/payment_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":%d,\"currency\":\"EUR\",\"capture_method\":\"manual\"}".formatted(amount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capture_method").value("manual"))
                .andReturn().getResponse().getContentAsString();
        confirm(field(body, "client_secret"));
        return field(body, "id");
    }

    private long outboxCount(String id, String eventType) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE event_key = :id AND event_type = :type")
                .param("id", id).param("type", eventType).query(Long.class).single();
    }

    @Test
    void aManualPaymentIsHeldNotTakenUntilTheMerchantCaptures() throws Exception {
        String id = authorisedPayment(20_000);

        mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.status").value("requires_capture"))
                .andExpect(jsonPath("$.amount").value(20_000))
                .andExpect(jsonPath("$.amount_authorized").value(20_000))
                .andExpect(jsonPath("$.capture_before").isNotEmpty())
                .andExpect(jsonPath("$.attempts[0].status").value("authorized"));
        // The ledger only learns of a payment when money is taken, so nothing says "succeeded" yet.
        assertThat(outboxCount(id, "PaymentAuthorized")).isEqualTo(1);
        assertThat(outboxCount(id, "PaymentSucceeded")).isZero();
    }

    @Test
    void capturingLessThanWasHeldTakesOnlyThatAndTheRefundLimitFollows() throws Exception {
        String id = authorisedPayment(20_000);
        stubHold("/capture", 200);

        mvc.perform(post("/v1/payment_intents/%s/capture".formatted(id)).with(merchant())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount_to_capture\":15000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.amount").value(15_000))
                .andExpect(jsonPath("$.amount_authorized").value(20_000))
                .andExpect(jsonPath("$.attempts[0].status").value("succeeded"));
        ACQUIRER.verify(WireMock.postRequestedFor(urlEqualTo("/capture"))
                .withRequestBody(WireMock.matchingJsonPath("$.amount", WireMock.equalTo("15000")))
                .withRequestBody(WireMock.matchingJsonPath("$.acquirer_ref", WireMock.equalTo("auth_hold"))));

        // The event the ledger books carries the captured amount, not the held one.
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE event_key = :id AND event_type = 'PaymentSucceeded'")
                .param("id", id).query(String.class).single();
        assertThat(json.readTree(payload).path("payload").path("amount").asLong()).isEqualTo(15_000);

        // Capturing again is refused, and a refund can only go up to what was taken.
        mvc.perform(post("/v1/payment_intents/%s/capture".formatted(id)).with(merchant()))
                .andExpect(status().isConflict());
        refund(id, 15_001).andExpect(status().isBadRequest());
    }

    @Test
    void capturingWithoutAnAmountTakesEverythingThatWasHeld() throws Exception {
        String id = authorisedPayment(8_000);
        stubHold("/capture", 200);
        mvc.perform(post("/v1/payment_intents/%s/capture".formatted(id)).with(merchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.amount").value(8_000));
    }

    @Test
    void capturingMoreThanWasHeldIsRejectedAndNothingChanges() throws Exception {
        String id = authorisedPayment(20_000);
        mvc.perform(post("/v1/payment_intents/%s/capture".formatted(id)).with(merchant())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount_to_capture\":20001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_capture_amount"));
        mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.status").value("requires_capture"));
        ACQUIRER.verify(0, WireMock.postRequestedFor(urlEqualTo("/capture")));
    }

    @Test
    void aPaymentThatWasNotAuthorisedCannotBeCaptured() throws Exception {
        String id = field(createPayment(), "id");
        mvc.perform(post("/v1/payment_intents/%s/capture".formatted(id)).with(merchant()))
                .andExpect(status().isConflict());
    }

    @Test
    void releasingAHeldPaymentTellsTheAcquirerAndCancelsIt() throws Exception {
        String id = authorisedPayment(20_000);
        stubHold("/void", 200);
        mvc.perform(post("/v1/payment_intents/%s/cancel".formatted(id)).with(merchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("canceled"))
                .andExpect(jsonPath("$.attempts[0].status").value("canceled"));
        ACQUIRER.verify(WireMock.postRequestedFor(urlEqualTo("/void"))
                .withRequestBody(WireMock.matchingJsonPath("$.acquirer_ref", WireMock.equalTo("auth_hold"))));
    }

    @Test
    void whenTheAcquirerCannotBeReachedACaptureFailsAndTheHoldStaysCapturable() throws Exception {
        String id = authorisedPayment(20_000);
        stubHold("/capture", 503);
        mvc.perform(post("/v1/payment_intents/%s/capture".formatted(id)).with(merchant()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("acquirer_unavailable"));
        mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.status").value("requires_capture"));

        stubHold("/capture", 200);
        mvc.perform(post("/v1/payment_intents/%s/capture".formatted(id)).with(merchant()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded"));
    }

    @Test
    void anAuthorisationNobodyCapturedIsReleasedAfterSevenDays() throws Exception {
        String stale = authorisedPayment(12_000);
        stubHold("/void", 200);

        // Six days later it is still held; after eight it is released by the job.
        ((dev.cutpay.web.SimulatedClock) clock).advance(java.time.Duration.ofDays(6));
        paymentService.expireUncaptured(100);
        mvc.perform(get("/v1/payment_intents/" + stale).with(merchant()))
                .andExpect(jsonPath("$.status").value("requires_capture"));

        ((dev.cutpay.web.SimulatedClock) clock).advance(java.time.Duration.ofDays(2));
        paymentService.expireUncaptured(100);
        mvc.perform(get("/v1/payment_intents/" + stale).with(merchant()))
                .andExpect(jsonPath("$.status").value("canceled"))
                .andExpect(jsonPath("$.failure_code").value("authorization_expired"));
        assertThat(outboxCount(stale, "PaymentCanceled")).isEqualTo(1);

        // Running the job again does nothing more.
        paymentService.expireUncaptured(100);
        assertThat(outboxCount(stale, "PaymentCanceled")).isEqualTo(1);
    }
}
