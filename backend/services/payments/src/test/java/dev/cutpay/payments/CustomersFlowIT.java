package dev.cutpay.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** Customers, saved cards, setup intents and off-session payments. The card network is a WireMock stub. */
@SpringBootTest(properties = {"cutpay.acquirer.read-timeout=PT1S", "cutpay.outbox.poll-interval=PT0.2S"})
@AutoConfigureMockMvc
@Testcontainers
class CustomersFlowIT {

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
        stubToken("tok_visa", "4242", "fp_visa");
        stubToken("tok_mc", "4444", "fp_mc");
        stubAuthorize("""
                {"status":"approved","acquirer_ref":"auth_ok"}""");
        stubVerify("""
                {"status":"approved","acquirer_ref":"ver_ok"}""");
    }

    private void stubToken(String token, String last4, String fingerprint) {
        ACQUIRER.stubFor(WireMock.get(urlEqualTo("/tokens/" + token)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"token":"%s","brand":"visa","last4":"%s","exp_month":12,"exp_year":2099,
                         "fingerprint":"%s","card_type":"consumer","card_region":"eea"}""".formatted(token, last4,
                        fingerprint))));
    }

    private void stubAuthorize(String body) {
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/authorize")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubVerify(String body) {
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/verify")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId).claim("role", "owner"));
    }

    private RequestPostProcessor otherMerchant() {
        return jwt().jwt(j -> j.claim("mid", "mer_other").claim("role", "owner"));
    }

    private String field(String body, String name) {
        return json.readTree(body).get(name).asString();
    }

    private String createCustomer() throws Exception {
        return mvc.perform(post("/v1/customers").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anna@example.com\",\"name\":\"Anna Petrova\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.object").value("customer"))
                .andReturn().getResponse().getContentAsString();
    }

    /** Creates a setup intent and completes it with the given vault token. */
    private String saveCard(String customerId, String token) throws Exception {
        String setup = mvc.perform(post("/v1/setup_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer\":\"%s\",\"merchant_name\":\"Rose Cafe\"}".formatted(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("requires_payment_method"))
                .andReturn().getResponse().getContentAsString();
        String secret = field(setup, "client_secret");
        mvc.perform(post("/checkout/setup_intents/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"));
        return field(setup, "id");
    }

    private ResultActions methods(String customerId) throws Exception {
        return mvc.perform(get("/v1/customers/%s/payment_methods".formatted(customerId)).with(merchant()));
    }

    private ResultActions payOffSession(String customerId, String extra) throws Exception {
        return mvc.perform(post("/v1/payment_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1900,\"currency\":\"EUR\",\"customer\":\"%s\",\"off_session\":true%s}"
                        .formatted(customerId, extra)));
    }

    // --- customers ------------------------------------------------------------------------------------------

    @Test
    void aCustomerCanBeCreatedReadUpdatedListedAndDeleted() throws Exception {
        String customer = createCustomer();
        String id = field(customer, "id");

        mvc.perform(patch("/v1/customers/" + id).with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+359 88 123 4567\",\"address\":{\"city\":\"Sofia\",\"country\":\"BG\"},"
                                + "\"metadata\":{\"crm_id\":\"42\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+359 88 123 4567"))
                .andExpect(jsonPath("$.email").value("anna@example.com"))
                .andExpect(jsonPath("$.address.city").value("Sofia"))
                .andExpect(jsonPath("$.metadata.crm_id").value("42"));

        mvc.perform(get("/v1/customers").param("q", "petrova").with(merchant()))
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/v1/customers").param("q", "nobody").with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(delete("/v1/customers/" + id).with(merchant())).andExpect(status().isNoContent());
        mvc.perform(get("/v1/customers/" + id).with(merchant())).andExpect(status().isNotFound());
        mvc.perform(get("/v1/customers").with(merchant())).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE topic = 'customer.events' AND event_key = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void anotherMerchantCannotSeeOrChangeMyCustomer() throws Exception {
        String id = field(createCustomer(), "id");
        mvc.perform(get("/v1/customers/" + id).with(otherMerchant())).andExpect(status().isNotFound());
        mvc.perform(delete("/v1/customers/" + id).with(otherMerchant())).andExpect(status().isNotFound());
        mvc.perform(post("/v1/setup_intents").with(otherMerchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customer\":\"%s\"}".formatted(id))).andExpect(status().isNotFound());
    }

    @Test
    void anAddressWithUnknownPartsAndABadEmailAreRejected() throws Exception {
        mvc.perform(post("/v1/customers").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_email"));
        mvc.perform(post("/v1/customers").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":{\"planet\":\"Mars\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_address"));
    }

    @Test
    void testAndLiveCustomersNeverMix() throws Exception {
        mvc.perform(post("/v1/customers").param("livemode", "true").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Live Larry\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.livemode").value(true));
        mvc.perform(get("/v1/customers").with(merchant())).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/v1/customers").param("livemode", "true").with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // --- saving a card --------------------------------------------------------------------------------------

    @Test
    void aCardSavedThroughASetupIntentBecomesTheDefaultAndOnlyShowsSafeDetails() throws Exception {
        String customerId = field(createCustomer(), "id");
        String setupId = saveCard(customerId, "tok_visa");

        methods(customerId)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].card.last4").value("4242"))
                .andExpect(jsonPath("$.data[0].card.brand").value("visa"))
                .andExpect(jsonPath("$.data[0].is_default").value(true))
                .andExpect(jsonPath("$.data[0].acquirer_token").doesNotExist());
        mvc.perform(get("/v1/setup_intents/" + setupId).with(merchant()))
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.payment_method").isNotEmpty());
        mvc.perform(get("/v1/customers/" + customerId).with(merchant()))
                .andExpect(jsonPath("$.default_payment_method").isNotEmpty());
    }

    @Test
    void savingTheSameCardTwiceKeepsOneAndASecondCardDoesNotTakeOverTheDefault() throws Exception {
        String customerId = field(createCustomer(), "id");
        saveCard(customerId, "tok_visa");
        saveCard(customerId, "tok_visa");
        methods(customerId).andExpect(jsonPath("$.data.length()").value(1));

        saveCard(customerId, "tok_mc");
        String body = methods(customerId).andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        // The newest card is listed first and the first card saved is still the default.
        assertThat(json.readTree(body).get("data").get(0).get("is_default").asBoolean()).isFalse();
        assertThat(json.readTree(body).get("data").get(1).get("is_default").asBoolean()).isTrue();
    }

    @Test
    void aCardThatNeedsAuthenticationIsSavedOnlyAfterTheCustomerAnswers() throws Exception {
        stubVerify("""
                {"status":"requires_action","acquirer_ref":"ver_3ds"}""");
        String customerId = field(createCustomer(), "id");
        String setup = mvc.perform(post("/v1/setup_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer\":\"%s\"}".formatted(customerId)))
                .andReturn().getResponse().getContentAsString();
        String secret = field(setup, "client_secret");

        mvc.perform(post("/checkout/setup_intents/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok_visa\"}"))
                .andExpect(jsonPath("$.status").value("requires_action"))
                .andExpect(jsonPath("$.next_action.type").value("three_ds"))
                .andExpect(jsonPath("$.next_action.challenge_id").value("ver_3ds"));
        methods(customerId).andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(post("/internal/3ds/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acquirer_ref\":\"ver_3ds\",\"status\":\"approved\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/checkout/setup_intents/" + secret)).andExpect(jsonPath("$.status").value("succeeded"));
        methods(customerId).andExpect(jsonPath("$.data.length()").value(1));

        // The same answer arriving twice changes nothing.
        mvc.perform(post("/internal/3ds/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acquirer_ref\":\"ver_3ds\",\"status\":\"approved\"}")).andExpect(status().isNoContent());
        methods(customerId).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void aFailedChallengeOrADeclinedCardSavesNothing() throws Exception {
        String customerId = field(createCustomer(), "id");

        stubVerify("""
                {"status":"declined","decline_code":"insufficient_funds","message":"Your card has insufficient funds."}""");
        String declined = mvc.perform(post("/v1/setup_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customer\":\"%s\"}".formatted(customerId))).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/checkout/setup_intents/%s/confirm".formatted(field(declined, "client_secret")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"tok_visa\"}"))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure_code").value("insufficient_funds"));

        stubVerify("""
                {"status":"requires_action","acquirer_ref":"ver_fail"}""");
        String challenged = mvc.perform(post("/v1/setup_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customer\":\"%s\"}".formatted(customerId))).andReturn().getResponse().getContentAsString();
        String secret = field(challenged, "client_secret");
        mvc.perform(post("/checkout/setup_intents/%s/confirm".formatted(secret)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"tok_visa\"}"));
        mvc.perform(post("/internal/3ds/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acquirer_ref\":\"ver_fail\",\"status\":\"declined\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/checkout/setup_intents/" + secret))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure_code").value("authentication_failed"));

        methods(customerId).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void aSetupIntentCannotBeConfirmedTwice() throws Exception {
        String customerId = field(createCustomer(), "id");
        String setup = mvc.perform(post("/v1/setup_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customer\":\"%s\"}".formatted(customerId))).andReturn().getResponse().getContentAsString();
        String url = "/checkout/setup_intents/%s/confirm".formatted(field(setup, "client_secret"));
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"tok_visa\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"tok_visa\"}"))
                .andExpect(status().isConflict());
    }

    // --- charging a saved card ------------------------------------------------------------------------------

    @Test
    void aSavedCardIsChargedWithoutTheCustomerAndTheBankIsToldNobodyIsThere() throws Exception {
        String customerId = field(createCustomer(), "id");
        saveCard(customerId, "tok_visa");

        payOffSession(customerId, "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.off_session").value(true))
                .andExpect(jsonPath("$.customer").value(customerId))
                .andExpect(jsonPath("$.payment_method").isNotEmpty())
                .andExpect(jsonPath("$.attempts[0].card_last4").value("4242"));
        ACQUIRER.verify(WireMock.postRequestedFor(urlEqualTo("/authorize"))
                .withRequestBody(WireMock.matchingJsonPath("$.token", WireMock.equalTo("tok_visa")))
                .withRequestBody(WireMock.matchingJsonPath("$.off_session", WireMock.equalTo("true"))));
    }

    @Test
    void aCardThatNeedsTheCustomerFailsOffSessionWithAReasonTheMerchantCanActOn() throws Exception {
        String customerId = field(createCustomer(), "id");
        saveCard(customerId, "tok_visa");
        stubAuthorize("""
                {"status":"declined","decline_code":"authentication_required",
                 "message":"Your bank requires authentication for this payment."}""");

        payOffSession(customerId, "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure_code").value("authentication_required"));
    }

    @Test
    void aSpecificSavedCardCanBeChosenAndItMustBelongToTheCustomer() throws Exception {
        String customerId = field(createCustomer(), "id");
        saveCard(customerId, "tok_visa");
        saveCard(customerId, "tok_mc");
        String mastercard = json.readTree(methods(customerId).andReturn().getResponse().getContentAsString())
                .get("data").get(0).get("id").asString();

        payOffSession(customerId, ",\"payment_method\":\"%s\"".formatted(mastercard))
                .andExpect(jsonPath("$.attempts[0].card_last4").value("4444"));

        String stranger = field(createCustomer(), "id");
        payOffSession(stranger, ",\"payment_method\":\"%s\"".formatted(mastercard))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("payment_method_not_found"));
    }

    @Test
    void aCustomerWithoutASavedCardCannotBeChargedOffSession() throws Exception {
        String customerId = field(createCustomer(), "id");
        payOffSession(customerId, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("no_payment_method"));
    }

    @Test
    void offSessionNeedsACustomer() throws Exception {
        mvc.perform(post("/v1/payment_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1900,\"currency\":\"EUR\",\"off_session\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("customer_required"));
    }

    @Test
    void aRemovedCardCanNoLongerBeChargedAndTheDefaultMovesOn() throws Exception {
        String customerId = field(createCustomer(), "id");
        saveCard(customerId, "tok_visa");
        saveCard(customerId, "tok_mc");
        String body = methods(customerId).andReturn().getResponse().getContentAsString();
        String newest = json.readTree(body).get("data").get(0).get("id").asString();
        String first = json.readTree(body).get("data").get(1).get("id").asString();

        mvc.perform(delete("/v1/customers/%s/payment_methods/%s".formatted(customerId, first)).with(merchant()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/customers/" + customerId).with(merchant()))
                .andExpect(jsonPath("$.default_payment_method").value(newest));
        payOffSession(customerId, ",\"payment_method\":\"%s\"".formatted(first)).andExpect(status().isBadRequest());
        payOffSession(customerId, "").andExpect(jsonPath("$.attempts[0].card_last4").value("4444"));
    }

    @Test
    void payingAtCheckoutCanSaveTheCardForLater() throws Exception {
        String customerId = field(createCustomer(), "id");
        String payment = mvc.perform(post("/v1/payment_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"amount\":2500,\"currency\":\"EUR\",\"customer\":\"%s\","
                                + "\"setup_future_usage\":\"off_session\"}").formatted(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer_email").value("anna@example.com"))
                .andReturn().getResponse().getContentAsString();
        methods(customerId).andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(field(payment, "client_secret")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"tok_visa\"}"))
                .andExpect(jsonPath("$.status").value("succeeded"));

        methods(customerId).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].card.last4").value("4242"));
        mvc.perform(get("/v1/payment_intents/" + field(payment, "id")).with(merchant()))
                .andExpect(jsonPath("$.payment_method").isNotEmpty());
    }

    @Test
    void paymentsCanBeListedPerCustomer() throws Exception {
        String anna = field(createCustomer(), "id");
        String boris = field(createCustomer(), "id");
        saveCard(anna, "tok_visa");
        saveCard(boris, "tok_visa");
        payOffSession(anna, "").andExpect(status().isCreated());
        payOffSession(anna, "").andExpect(status().isCreated());
        payOffSession(boris, "").andExpect(status().isCreated());

        mvc.perform(get("/v1/payment_intents").param("customer", anna).with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/v1/payment_intents").param("customer", boris).with(merchant()))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void savedCardsAreAnnouncedOnTheEventStream() throws Exception {
        String customerId = field(createCustomer(), "id");
        saveCard(customerId, "tok_visa");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE topic = 'customer.events' AND event_type = :type")
                .param("type", "PaymentMethodAttached").query(Long.class).single()).isGreaterThanOrEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = 'SetupIntentSucceeded'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(1L);
    }
}
