package dev.cutpay.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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

/** Payments shared between a platform and its connected accounts. Identity and the card network are WireMock stubs. */
@SpringBootTest(properties = {"cutpay.acquirer.read-timeout=PT1S", "cutpay.outbox.poll-interval=PT0.2S"})
@AutoConfigureMockMvc
@Testcontainers
class SplitPaymentsFlowIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static final WireMockServer ACQUIRER = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final WireMockServer IDENTITY = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        ACQUIRER.start();
        IDENTITY.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("cutpay.acquirer.url", ACQUIRER::baseUrl);
        registry.add("cutpay.identity-url", IDENTITY::baseUrl);
    }

    @AfterAll
    static void stop() {
        ACQUIRER.stop();
        IDENTITY.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    private String platform;

    @BeforeEach
    void freshPlatformAndStubs() {
        platform = "mer_" + UUID.randomUUID().toString().replace("-", "");
        ACQUIRER.resetAll();
        IDENTITY.resetAll();
        account("acct_pizza", "active", "EUR", false);
        account("acct_burger", "active", "EUR", false);
        account("acct_off", "disabled", "EUR", false);
        account("acct_zloty", "active", "PLN", false);
        account("acct_live", "active", "EUR", true);
        IDENTITY.stubFor(WireMock.get(urlPathMatching("/internal/platforms/.+/connected_accounts/acct_unknown"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"connected_account_not_found\",\"message\":\"No such account\"}}")));
        ACQUIRER.stubFor(WireMock.get(urlEqualTo("/tokens/tok_visa")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"token":"tok_visa","brand":"visa","last4":"4242","exp_month":12,"exp_year":2099,
                         "fingerprint":"fp_1"}""")));
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/authorize")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"status\":\"approved\",\"acquirer_ref\":\"auth_1\"}")));
    }

    private void account(String id, String status, String currency, boolean livemode) {
        IDENTITY.stubFor(WireMock.get(urlPathMatching("/internal/platforms/.+/connected_accounts/" + id)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"%s\",\"status\":\"%s\",\"currency\":\"%s\",\"livemode\":%s}".formatted(id, status, currency, livemode))));
    }

    private RequestPostProcessor platform() {
        return jwt().jwt(j -> j.claim("mid", platform).claim("role", "owner"));
    }

    private ResultActions create(String extra) throws Exception {
        return mvc.perform(post("/v1/payment_intents").with(platform()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":10000,\"currency\":\"EUR\"%s}".formatted(extra)));
    }

    @Test
    void aPaymentCanBeSharedBetweenAccountsAndThePlatformKeepsTheRest() throws Exception {
        String body = create(",\"transfer_data\":[{\"destination\":\"acct_pizza\",\"amount\":6000},{\"destination\":\"acct_burger\",\"amount\":1000}]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transfer_data.length()").value(2))
                .andExpect(jsonPath("$.transfer_data[?(@.destination=='acct_burger')].amount").value(1000))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asString();
        mvc.perform(get("/v1/payment_intents/" + id).with(platform())).andExpect(jsonPath("$.transfer_data.length()").value(2));
    }

    @Test
    void anApplicationFeeLetsTheSingleDestinationReceiveEverythingElse() throws Exception {
        String body = create(",\"transfer_data\":[{\"destination\":\"acct_pizza\"}],\"application_fee_amount\":1500")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.application_fee_amount").value(1500))
                .andExpect(jsonPath("$.transfer_data[0].amount").value(8500))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("transfer_data").size()).isEqualTo(1);
    }

    @Test
    void thePaymentSucceededEventCarriesTheTransfersForTheLedger() throws Exception {
        String body = create(",\"transfer_data\":[{\"destination\":\"acct_pizza\",\"amount\":7000}]")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(json.readTree(body).get("client_secret").asString()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"tok_visa\"}"))
                .andExpect(jsonPath("$.status").value("succeeded"));

        String payload = jdbc.sql("SELECT payload FROM outbox WHERE event_key = :id AND event_type = 'PaymentSucceeded'")
                .param("id", json.readTree(body).get("id").asString()).query(String.class).single();
        var transfers = json.readTree(payload).path("payload").path("transfers");
        assertThat(transfers.size()).isEqualTo(1);
        assertThat(transfers.get(0).get("destination").asString()).isEqualTo("acct_pizza");
        assertThat(transfers.get(0).get("amount").asLong()).isEqualTo(7000);
    }

    @Test
    void anOrdinaryPaymentHasNoTransfers() throws Exception {
        create("").andExpect(status().isCreated()).andExpect(jsonPath("$.transfer_data").isEmpty())
                .andExpect(jsonPath("$.application_fee_amount").doesNotExist());
    }

    @Test
    void aDestinationThatDoesNotExistOrIsDisabledOrLiveIsRefused() throws Exception {
        create(",\"transfer_data\":[{\"destination\":\"acct_unknown\",\"amount\":1000}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("connected_account_not_found"));
        create(",\"transfer_data\":[{\"destination\":\"acct_off\",\"amount\":1000}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("connected_account_disabled"));
        // A live account cannot receive test money.
        create(",\"transfer_data\":[{\"destination\":\"acct_live\",\"amount\":1000}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("connected_account_not_found"));
    }

    @Test
    void anAccountPaidOutInAnotherCurrencyCannotReceiveThePayment() throws Exception {
        create(",\"transfer_data\":[{\"destination\":\"acct_zloty\",\"amount\":1000}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("currency_mismatch"));
    }

    @Test
    void transfersThatAddUpToMoreThanThePaymentAreRefused() throws Exception {
        create(",\"transfer_data\":[{\"destination\":\"acct_pizza\",\"amount\":6000},{\"destination\":\"acct_burger\",\"amount\":4001}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("transfers_exceed_payment"));
        // Sending everything is fine: the platform then pays the fee out of its own pocket.
        create(",\"transfer_data\":[{\"destination\":\"acct_pizza\",\"amount\":6000},{\"destination\":\"acct_burger\",\"amount\":4000}]")
                .andExpect(status().isCreated());
    }

    @Test
    void theTwoWaysToSplitCannotBeMixedAndAFeeNeedsADestination() throws Exception {
        create(",\"transfer_data\":[{\"destination\":\"acct_pizza\",\"amount\":6000}],\"application_fee_amount\":500")
                .andExpect(jsonPath("$.error.code").value("ambiguous_split"));
        create(",\"transfer_data\":[{\"destination\":\"acct_pizza\"},{\"destination\":\"acct_burger\"}],\"application_fee_amount\":500")
                .andExpect(jsonPath("$.error.code").value("ambiguous_split"));
        create(",\"transfer_data\":[{\"destination\":\"acct_pizza\"}]").andExpect(jsonPath("$.error.code").value("amount_required"));
        create(",\"application_fee_amount\":500").andExpect(jsonPath("$.error.code").value("destination_required"));
        create(",\"transfer_data\":[{\"destination\":\"acct_pizza\"}],\"application_fee_amount\":10000")
                .andExpect(jsonPath("$.error.code").value("fee_too_large"));
    }

    @Test
    void theSameAccountCannotAppearTwiceAndAHeldPaymentCannotBeSplit() throws Exception {
        create(",\"transfer_data\":[{\"destination\":\"acct_pizza\",\"amount\":100},{\"destination\":\"acct_pizza\",\"amount\":200}]")
                .andExpect(jsonPath("$.error.code").value("duplicate_destination"));
        create(",\"capture_method\":\"manual\",\"transfer_data\":[{\"destination\":\"acct_pizza\",\"amount\":100}]")
                .andExpect(jsonPath("$.error.code").value("transfers_need_automatic_capture"));
    }

    @Test
    void aSplitIsNotStoredWhenOneOfItsDestinationsIsRefused() throws Exception {
        long before = jdbc.sql("SELECT count(*) FROM payment_intents WHERE merchant_id = :m").param("m", platform)
                .query(Long.class).single();
        create(",\"transfer_data\":[{\"destination\":\"acct_pizza\",\"amount\":100},{\"destination\":\"acct_unknown\",\"amount\":100}]")
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM payment_intents WHERE merchant_id = :m").param("m", platform)
                .query(Long.class).single()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM payment_transfers t JOIN payment_intents p ON p.id = t.payment_intent_id WHERE p.merchant_id = :m")
                .param("m", platform).query(Long.class).single()).isZero();
    }
}
