package dev.cutpay.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
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
}
