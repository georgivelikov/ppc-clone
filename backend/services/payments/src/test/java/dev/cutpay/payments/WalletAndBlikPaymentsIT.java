package dev.cutpay.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Paying with a wallet device token, and with a BLIK code that the customer confirms in their banking app. */
@SpringBootTest(properties = {"cutpay.acquirer.read-timeout=PT1S", "cutpay.outbox.poll-interval=PT0.2S"})
@AutoConfigureMockMvc
@Testcontainers
class WalletAndBlikPaymentsIT {

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
    void freshMerchant() {
        merchantId = "mer_" + UUID.randomUUID().toString().replace("-", "");
        ACQUIRER.resetAll();
        ACQUIRER.stubFor(WireMock.get(urlEqualTo("/tokens/tok_gpay")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token\":\"tok_gpay\",\"brand\":\"visa\",\"last4\":\"3220\",\"exp_month\":12,\"exp_year\":2099,"
                        + "\"fingerprint\":\"fp_1\",\"card_type\":\"consumer\",\"card_region\":\"eea\",\"wallet_type\":\"google_pay\"}")));
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/authorize")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"status\":\"approved\",\"acquirer_ref\":\"auth_1\"}")));
    }

    private RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId).claim("role", "owner"));
    }

    private String create(String currency, String extra) throws Exception {
        return mvc.perform(post("/v1/payment_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10000,\"currency\":\"%s\",\"merchant_name\":\"Rose Cafe\"%s}".formatted(currency, extra)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private String field(String body, String name) {
        return json.readTree(body).get(name).asString();
    }

    private ResultActions confirm(String secret, String body) throws Exception {
        return mvc.perform(post("/checkout/sessions/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void blikAnswer(String ref, String status, String code) throws Exception {
        mvc.perform(post("/internal/blik/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acquirer_ref\":\"%s\",\"status\":\"%s\",\"decline_code\":\"%s\",\"message\":\"\"}"
                                .formatted(ref, status, code)))
                .andExpect(status().isNoContent());
    }

    private String eventPayload(String paymentId, String type) {
        return jdbc.sql("SELECT payload FROM outbox WHERE event_key = :id AND event_type = :type ORDER BY id DESC LIMIT 1")
                .param("id", paymentId).param("type", type).query(String.class).single();
    }

    private void stubBlik(String responseBody) {
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/blik/charge")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(responseBody)));
    }

    @Test
    void aWalletPaymentSucceedsAndIsRecordedAsThatWalletWithoutAnyChallenge() throws Exception {
        String created = create("EUR", "");
        confirm(field(created, "client_secret"), "{\"token\":\"tok_gpay\",\"email\":\"anna@example.com\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded"));

        mvc.perform(get("/v1/payment_intents/" + field(created, "id")).with(merchant()))
                .andExpect(jsonPath("$.payment_method_type").value("google_pay"))
                .andExpect(jsonPath("$.attempts[0].method_type").value("google_pay"))
                .andExpect(jsonPath("$.attempts[0].card_last4").value("3220"));
        // Priced as the card behind it.
        assertThat(eventPayload(field(created, "id"), "PaymentSucceeded"))
                .contains("\"payment_method_type\":\"google_pay\"").contains("\"card_type\":\"consumer\"");
    }

    @Test
    void aBlikPaymentWaitsForTheBankAppAndSucceedsWhenTheCustomerApproves() throws Exception {
        stubBlik("{\"status\":\"requires_action\",\"acquirer_ref\":\"blik_1\"}");
        String created = create("PLN", "");
        String secret = field(created, "client_secret");

        confirm(secret, "{\"method\":\"blik\",\"blik_code\":\"777123\"}")
                .andExpect(jsonPath("$.status").value("requires_action"))
                .andExpect(jsonPath("$.next_action.type").value("blik"));
        // Asking again while it waits is refused.
        confirm(secret, "{\"method\":\"blik\",\"blik_code\":\"777123\"}").andExpect(status().isConflict());

        blikAnswer("blik_1", "approved", "");
        blikAnswer("blik_1", "approved", "");
        mvc.perform(get("/checkout/sessions/" + secret)).andExpect(jsonPath("$.status").value("succeeded"));
        mvc.perform(get("/v1/payment_intents/" + field(created, "id")).with(merchant()))
                .andExpect(jsonPath("$.payment_method_type").value("blik"))
                .andExpect(jsonPath("$.attempts[0].method_type").value("blik"))
                .andExpect(jsonPath("$.attempts[0].status").value("succeeded"));
        assertThat(eventPayload(field(created, "id"), "PaymentSucceeded")).contains("\"payment_method_type\":\"blik\"");
    }

    @Test
    void aRequestRejectedInTheBankAppFailsThePayment() throws Exception {
        stubBlik("{\"status\":\"requires_action\",\"acquirer_ref\":\"blik_2\"}");
        String created = create("PLN", "");
        String secret = field(created, "client_secret");
        confirm(secret, "{\"method\":\"blik\",\"blik_code\":\"777123\"}").andExpect(status().isOk());

        blikAnswer("blik_2", "declined", "blik_rejected");
        mvc.perform(get("/checkout/sessions/" + secret)).andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure_code").value("blik_rejected"));
    }

    @Test
    void aBankThatDeclinesTheCodeFailsThePayment() throws Exception {
        stubBlik("{\"status\":\"declined\",\"decline_code\":\"blik_declined\",\"message\":\"Your bank declined the BLIK payment.\"}");
        String secret = field(create("PLN", ""), "client_secret");
        confirm(secret, "{\"method\":\"blik\",\"blik_code\":\"000000\"}")
                .andExpect(jsonPath("$.status").value("failed")).andExpect(jsonPath("$.failure_code").value("blik_declined"));
    }

    @Test
    void aCodeThatIsNotValidLeavesThePaymentOpenForAnotherTry() throws Exception {
        stubBlik("{\"status\":\"invalid_code\",\"decline_code\":\"invalid_blik_code\",\"message\":\"That BLIK code is not valid.\"}");
        String secret = field(create("PLN", ""), "client_secret");

        confirm(secret, "{\"method\":\"blik\",\"blik_code\":\"123456\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_blik_code"));
        confirm(secret, "{\"method\":\"blik\",\"blik_code\":\"12ab\"}").andExpect(status().isBadRequest());
        mvc.perform(get("/checkout/sessions/" + secret)).andExpect(jsonPath("$.status").value("requires_payment_method"));
    }

    @Test
    void blikIsOnlyForPlnAndForPaymentsTakenAtOnce() throws Exception {
        confirm(field(create("EUR", ""), "client_secret"), "{\"method\":\"blik\",\"blik_code\":\"777123\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("blik_pln_only"));
        confirm(field(create("PLN", ",\"capture_method\":\"manual\""), "client_secret"),
                "{\"method\":\"blik\",\"blik_code\":\"777123\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("blik_automatic_capture_only"));
    }

    @Test
    void theCheckoutIsToldWhichMethodsItCanOffer() throws Exception {
        mvc.perform(get("/checkout/sessions/" + field(create("PLN", ""), "client_secret") + "/methods"))
                .andExpect(jsonPath("$.methods[3]").value("blik"));
        mvc.perform(get("/checkout/sessions/" + field(create("EUR", ""), "client_secret") + "/methods"))
                .andExpect(jsonPath("$.methods.length()").value(3));
    }

    @Test
    void anAnswerForARequestNobodyKnowsIsIgnored() throws Exception {
        blikAnswer("blik_nobody", "approved", "");
    }
}
