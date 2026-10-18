package dev.cutpay.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payments settled by a buy-now-pay-later provider: the aggregator's {@code BnplApproved} event arrives on Kafka and the
 * payment is completed, unless it can no longer be.
 */
@SpringBootTest(properties = {"cutpay.acquirer.read-timeout=PT1S", "cutpay.outbox.poll-interval=PT0.2S"})
@AutoConfigureMockMvc
@Testcontainers
class BnplPaymentsIT {

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

    @Autowired
    KafkaTemplate<String, String> kafka;

    private String merchantId;

    @BeforeEach
    void freshMerchant() {
        merchantId = "mer_" + UUID.randomUUID().toString().replace("-", "");
        ACQUIRER.resetAll();
        ACQUIRER.stubFor(WireMock.get(urlEqualTo("/tokens/tok_visa")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token\":\"tok_visa\",\"brand\":\"visa\",\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2099,\"fingerprint\":\"fp_1\"}")));
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/authorize")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"status\":\"approved\",\"acquirer_ref\":\"auth_1\"}")));
    }

    private RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId).claim("role", "owner"));
    }

    private String create(String extra) throws Exception {
        return mvc.perform(post("/v1/payment_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":30000,\"currency\":\"EUR\",\"merchant_name\":\"Rose Cafe\"%s}".formatted(extra)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private String field(String body, String name) {
        return json.readTree(body).get(name).asString();
    }

    /** The event the aggregator publishes when a provider approves an application. */
    private void approved(String eventId, String paymentId, String applicationId, long amount, long fee) {
        kafka.send("bnpl.events", applicationId, """
                {"event_id":"%s","event_type":"BnplApproved","occurred_at":"2026-10-19T10:00:00Z","aggregate_id":"%s",
                 "merchant_id":"%s","livemode":false,"version":1,
                 "payload":{"application_id":"%s","payment_id":"%s","provider":"splitnow","plan":"splitnow_3","amount":%d,
                            "currency":"EUR","merchant_fee":%d,"status":"approved"}}"""
                .formatted(eventId, applicationId, merchantId, applicationId, paymentId, amount, fee));
    }

    private long outboxCount(String id, String type) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE event_key = :id AND event_type = :type")
                .param("id", id).param("type", type).query(Long.class).single();
    }

    private String payload(String id, String type) {
        return jdbc.sql("SELECT payload FROM outbox WHERE event_key = :id AND event_type = :type ORDER BY id LIMIT 1")
                .param("id", id).param("type", type).query(String.class).single();
    }

    private void awaitStatus(String id, String expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.status").value(expected)));
    }

    @Test
    void anApprovedApplicationCompletesThePaymentAndTellsTheLedgerWhatTheProviderKeeps() throws Exception {
        String id = field(create(""), "id");

        approved("evt_" + UUID.randomUUID(), id, "bnpl_1", 30_000, 1050);

        awaitStatus(id, "succeeded");
        mvc.perform(get("/v1/payment_intents/" + id).with(merchant()))
                .andExpect(jsonPath("$.payment_method_type").value("bnpl"))
                .andExpect(jsonPath("$.bnpl.provider").value("splitnow")).andExpect(jsonPath("$.bnpl.application").value("bnpl_1"))
                .andExpect(jsonPath("$.bnpl.fee").value(1050))
                .andExpect(jsonPath("$.attempts.length()").value(1)).andExpect(jsonPath("$.attempts[0].method_type").value("bnpl"))
                .andExpect(jsonPath("$.attempts[0].status").value("succeeded"));
        assertThat(outboxCount(id, "PaymentSucceeded")).isEqualTo(1);
        var event = json.readTree(payload(id, "PaymentSucceeded")).get("payload");
        assertThat(event.get("payment_method_type").asString()).isEqualTo("bnpl");
        assertThat(event.get("bnpl_provider").asString()).isEqualTo("splitnow");
        assertThat(event.get("bnpl_application_id").asString()).isEqualTo("bnpl_1");
        assertThat(event.get("bnpl_fee").asLong()).isEqualTo(1050);
        assertThat(event.get("amount").asLong()).isEqualTo(30_000);
    }

    @Test
    void theSameApprovalDeliveredTwiceOrAgainstTheSameApplicationCompletesThePaymentOnce() throws Exception {
        String id = field(create(""), "id");
        String eventId = "evt_" + UUID.randomUUID();

        approved(eventId, id, "bnpl_1", 30_000, 1050);
        approved(eventId, id, "bnpl_1", 30_000, 1050);
        approved("evt_" + UUID.randomUUID(), id, "bnpl_1", 30_000, 1050);

        awaitStatus(id, "succeeded");
        // Give the duplicates time to be handled before counting.
        approved("evt_" + UUID.randomUUID(), "pay_unknown", "bnpl_2", 100, 1);
        await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(outboxCount(id, "PaymentSucceeded")).isEqualTo(1));
        assertThat(outboxCount(id, "BnplPaymentRejected")).isZero();
    }

    @Test
    void aPaymentThatWasPaidByCardInTheMeantimeRefusesTheApprovalAndTheAggregatorIsTold() throws Exception {
        String created = create("");
        String id = field(created, "id");
        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(field(created, "client_secret")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"tok_visa\",\"email\":\"anna@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded"));

        approved("evt_" + UUID.randomUUID(), id, "bnpl_late", 30_000, 1050);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(outboxCount(id, "BnplPaymentRejected")).isEqualTo(1));
        var rejection = json.readTree(payload(id, "BnplPaymentRejected")).get("payload");
        assertThat(rejection.get("application_id").asString()).isEqualTo("bnpl_late");
        assertThat(rejection.get("reason").asString()).isEqualTo("payment_not_payable");
        mvc.perform(get("/v1/payment_intents/" + id).with(merchant())).andExpect(jsonPath("$.payment_method_type").value("card"))
                .andExpect(jsonPath("$.bnpl").doesNotExist());
        assertThat(outboxCount(id, "PaymentSucceeded")).isEqualTo(1);
    }

    @Test
    void approvalsThatDoNotMatchThePaymentAreRefused() throws Exception {
        String wrongAmount = field(create(""), "id");
        approved("evt_" + UUID.randomUUID(), wrongAmount, "bnpl_a", 29_999, 1050);
        String held = field(create(",\"capture_method\":\"manual\""), "id");
        approved("evt_" + UUID.randomUUID(), held, "bnpl_b", 30_000, 1050);
        String silly = field(create(""), "id");
        approved("evt_" + UUID.randomUUID(), silly, "bnpl_c", 30_000, 30_001);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(outboxCount(wrongAmount, "BnplPaymentRejected")).isEqualTo(1);
            assertThat(outboxCount(held, "BnplPaymentRejected")).isEqualTo(1);
            assertThat(outboxCount(silly, "BnplPaymentRejected")).isEqualTo(1);
        });
        assertThat(json.readTree(payload(wrongAmount, "BnplPaymentRejected")).get("payload").get("reason").asString()).isEqualTo("amount_mismatch");
        assertThat(json.readTree(payload(held, "BnplPaymentRejected")).get("payload").get("reason").asString()).isEqualTo("capture_method_manual");
        assertThat(json.readTree(payload(silly, "BnplPaymentRejected")).get("payload").get("reason").asString()).isEqualTo("invalid_fee");
        mvc.perform(get("/v1/payment_intents/" + wrongAmount).with(merchant())).andExpect(jsonPath("$.status").value("requires_payment_method"));
    }

    @Test
    void otherBnplEventsDoNotTouchThePayment() throws Exception {
        String id = field(create(""), "id");
        for (String type : new String[] {"BnplApplicationCreated", "BnplDeclined", "BnplExpired", "BnplCanceled", "BnplProviderTimedOut"}) {
            kafka.send("bnpl.events", "bnpl_x", """
                    {"event_id":"evt_%s","event_type":"%s","occurred_at":"2026-10-19T10:00:00Z","aggregate_id":"bnpl_x",
                     "merchant_id":"%s","livemode":false,"version":1,
                     "payload":{"application_id":"bnpl_x","payment_id":"%s","provider":"splitnow","amount":30000,"currency":"EUR"}}"""
                    .formatted(UUID.randomUUID(), type, merchantId, id));
        }
        // A real approval after them still works: they were read and ignored, not stuck.
        approved("evt_" + UUID.randomUUID(), id, "bnpl_ok", 30_000, 1050);

        awaitStatus(id, "succeeded");
        assertThat(outboxCount(id, "PaymentFailed")).isZero();
    }

    @Test
    void theCheckoutFactsSayWhetherAPaymentIsSplitAndCanBePaidInInstalments() throws Exception {
        String created = create("");
        mvc.perform(get("/internal/checkout/" + field(created, "client_secret")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.split").value(false))
                .andExpect(jsonPath("$.status").value("requires_payment_method")).andExpect(jsonPath("$.capture_method").value("automatic"));
    }
}
