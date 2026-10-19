package dev.cutpay.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.cutpay.payments.dispute.DisputeService;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** A card that gets disputed: the dispute opens by itself, the merchant answers, the card network decides. */
@SpringBootTest(properties = {
        "cutpay.outbox.poll-interval=PT0.2S",
        "cutpay.payments.dispute-poll-interval=PT1H", // the tests move the disputes along themselves
        "cutpay.payments.dispute-review-delay=PT0S"})
@AutoConfigureMockMvc
@Testcontainers
class DisputesIT {

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
    DisputeService disputes;

    private String merchantId;

    @BeforeEach
    void freshMerchant() {
        merchantId = "mer_" + UUID.randomUUID().toString().replace("-", "");
        ACQUIRER.resetAll();
        ACQUIRER.stubFor(WireMock.get(urlEqualTo("/tokens/tok_disputed")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token\":\"tok_disputed\",\"brand\":\"visa\",\"last4\":\"0259\",\"exp_month\":12,\"exp_year\":2099,"
                        + "\"fingerprint\":\"fp_d\",\"card_type\":\"consumer\",\"card_region\":\"eea\",\"chargeback_after_days\":1}")));
        ACQUIRER.stubFor(WireMock.get(urlEqualTo("/tokens/tok_plain")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token\":\"tok_plain\",\"brand\":\"visa\",\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2099,"
                        + "\"fingerprint\":\"fp_p\",\"card_type\":\"consumer\",\"card_region\":\"eea\"}")));
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/authorize")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"status\":\"approved\",\"acquirer_ref\":\"auth_1\"}")));
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/refund")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"status\":\"succeeded\",\"refund_ref\":\"ref_1\"}")));
    }

    private RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId).claim("role", "owner"));
    }

    /** A paid payment; the card decides whether a dispute is planned. */
    private String pay(String token) throws Exception {
        String created = mvc.perform(post("/v1/payment_intents").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10000,\"currency\":\"EUR\",\"merchant_name\":\"Rose Cafe\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/checkout/sessions/%s/confirm".formatted(json.readTree(created).get("client_secret").asString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"email\":\"anna@example.com\"}".formatted(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded"));
        return json.readTree(created).get("id").asString();
    }

    /** Lets the day pass: the scheduled dispute is due, and opens. Returns its id. */
    private String openDisputeFor(String paymentId) {
        jdbc.sql("UPDATE dispute_schedule SET open_at = now() - interval '1 minute' WHERE payment_id = :p").param("p", paymentId).update();
        assertThat(disputes.openDue(10)).isEqualTo(1);
        return jdbc.sql("SELECT id FROM disputes WHERE payment_id = :p").param("p", paymentId).query(String.class).single();
    }

    private String dispute(String id) throws Exception {
        return mvc.perform(get("/v1/disputes/" + id).with(merchant())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void evidence(String id, String text) throws Exception {
        mvc.perform(post("/v1/disputes/" + id + "/evidence").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidence\":\"%s\"}".formatted(text)))
                .andExpect(status().isOk());
    }

    private void networkDecides(String outcome) {
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/disputes/evaluate")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"outcome\":\"%s\",\"reason\":\"because\"}".formatted(outcome))));
    }

    @Test
    void aDisputedCardIsPlannedAndAPlainCardIsNot() throws Exception {
        String disputed = pay("tok_disputed");
        String plain = pay("tok_plain");
        assertThat(jdbc.sql("SELECT count(*) FROM dispute_schedule WHERE payment_id = :p").param("p", disputed).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM dispute_schedule WHERE payment_id = :p").param("p", plain).query(Integer.class).single()).isZero();
        // It is a day away, so nothing opens yet.
        assertThat(disputes.openDue(10)).isZero();
    }

    @Test
    void theDisputeOpensHoldsTheRefundAndIsWonWithEvidence() throws Exception {
        String paymentId = pay("tok_disputed");
        String id = openDisputeFor(paymentId);

        String opened = dispute(id);
        assertThat(json.readTree(opened).get("status").asString()).isEqualTo("needs_response");
        assertThat(json.readTree(opened).get("amount").asLong()).isEqualTo(10000);
        assertThat(json.readTree(opened).get("fee").asLong()).isEqualTo(1500);
        assertThat(json.readTree(opened).get("reason").asString()).isEqualTo("fraudulent");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE event_key = :id AND event_type = 'DisputeOpened'").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);

        // The money is already held, so it cannot also be refunded.
        mvc.perform(post("/v1/refunds").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_intent\":\"%s\"}".formatted(paymentId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("payment_disputed"));

        mvc.perform(get("/v1/disputes").with(merchant()).param("status", "needs_response"))
                .andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(post("/v1/disputes/" + id + "/evidence").with(merchant()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("evidence_required"));
        evidence(id, "Delivered on 3 October, see winning_evidence");
        assertThat(json.readTree(dispute(id)).get("status").asString()).isEqualTo("under_review");
        mvc.perform(post("/v1/disputes/" + id + "/evidence").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidence\":\"again\"}"))
                .andExpect(status().isConflict());

        networkDecides("won");
        assertThat(disputes.decideReviews(10)).isEqualTo(1);
        assertThat(json.readTree(dispute(id)).get("status").asString()).isEqualTo("won");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE event_key = :id AND event_type = 'DisputeClosed' AND payload LIKE '%\"outcome\":\"won\"%'")
                .param("id", id).query(Integer.class).single()).isEqualTo(1);
        // Once settled it is no longer in the way of a refund.
        mvc.perform(post("/v1/refunds").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_intent\":\"%s\",\"amount\":500}".formatted(paymentId)))
                .andExpect(status().isCreated());
    }

    @Test
    void noAnswerBeforeTheDeadlineLosesAndSoDoesGivingUp() throws Exception {
        String slow = openDisputeFor(pay("tok_disputed"));
        jdbc.sql("UPDATE disputes SET evidence_due_by = now() - interval '1 minute' WHERE id = :id").param("id", slow).update();
        mvc.perform(post("/v1/disputes/" + slow + "/evidence").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidence\":\"too late\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("evidence_overdue"));
        assertThat(disputes.closeOverdue(10)).isGreaterThanOrEqualTo(1);
        assertThat(json.readTree(dispute(slow)).get("status").asString()).isEqualTo("lost");
        assertThat(json.readTree(dispute(slow)).get("outcome_reason").asString()).contains("deadline");

        String conceded = openDisputeFor(pay("tok_disputed"));
        mvc.perform(post("/v1/disputes/" + conceded + "/accept").with(merchant()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("lost"));
        mvc.perform(post("/v1/disputes/" + conceded + "/accept").with(merchant())).andExpect(status().isConflict());
    }

    @Test
    void evidenceFilesAreStoredLimitedAndServedAsDownloads() throws Exception {
        String id = openDisputeFor(pay("tok_disputed"));
        var file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", "%PDF-fake".getBytes());
        String fileId = json.readTree(mvc.perform(multipart("/v1/disputes/" + id + "/files").file(file).with(merchant()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.filename").value("invoice.pdf"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        mvc.perform(get("/v1/disputes/" + id + "/files/" + fileId).with(merchant()))
                .andExpect(status().isOk())
                .andExpect(content().bytes("%PDF-fake".getBytes()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/octet-stream"));
        // A different merchant cannot read it.
        mvc.perform(get("/v1/disputes/" + id + "/files/" + fileId)
                        .with(jwt().jwt(j -> j.claim("mid", "mer_other").claim("role", "owner"))))
                .andExpect(status().isNotFound());

        mvc.perform(multipart("/v1/disputes/" + id + "/files").file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])).with(merchant()))
                .andExpect(status().isBadRequest());
        // A file alone is enough evidence to send.
        mvc.perform(post("/v1/disputes/" + id + "/evidence").with(merchant()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("under_review"))
                .andExpect(jsonPath("$.files.length()").value(1));
        // After that no more files.
        mvc.perform(multipart("/v1/disputes/" + id + "/files").file(file).with(merchant())).andExpect(status().isConflict());
    }

    @Test
    void aNetworkThatDoesNotAnswerLeavesTheDisputeUnderReview() throws Exception {
        String id = openDisputeFor(pay("tok_disputed"));
        evidence(id, "some evidence");
        ACQUIRER.stubFor(WireMock.post(urlEqualTo("/disputes/evaluate")).willReturn(aResponse().withStatus(503)));
        assertThat(disputes.decideReviews(10)).isZero();
        assertThat(json.readTree(dispute(id)).get("status").asString()).isEqualTo("under_review");
        networkDecides("lost");
        assertThat(disputes.decideReviews(10)).isGreaterThanOrEqualTo(1); // others left under review by other tests are decided too
        assertThat(json.readTree(dispute(id)).get("status").asString()).isEqualTo("lost");
    }
}
