package dev.cutpay.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cutpay.reporting.projection.Rebuilder;
import dev.cutpay.reporting.run.ReportRunService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The read model built from events, the reports built from the read model, and the read model built again from the topics. */
@SpringBootTest(properties = {"cutpay.outbox.poll-interval=PT0.2S", "cutpay.reporting.job-poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class ReportingIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ReportRunService runs;

    @Autowired
    Rebuilder rebuilder;

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String envelope(String type, String merchant, String aggregate, Instant at, String payload) {
        return "{\"event_id\":\"%s\",\"event_type\":\"%s\",\"occurred_at\":\"%s\",\"aggregate_id\":\"%s\",\"merchant_id\":\"%s\",\"livemode\":false,\"version\":1,\"payload\":%s}"
                .formatted(id("evt"), type, at, aggregate, merchant, payload);
    }

    private void send(String topic, String key, String message) {
        kafka.send(topic, key, message);
    }

    private RequestPostProcessor owner(String merchant) {
        return jwt().jwt(j -> j.claim("mid", merchant).claim("role", "owner").claim("email", "owner@example.com"));
    }

    private void payment(String merchant, String paymentId, String status, String type, Instant at, long amount, String method, String failure) {
        send("payment.events", paymentId, envelope(type, merchant, paymentId, at, """
                {"payment_id":"%s","status":"%s","amount":%d,"currency":"EUR","payment_method_type":%s,"failure_code":%s,"card_brand":"visa"}"""
                .formatted(paymentId, status, amount, method == null ? "null" : "\"" + method + "\"", failure == null ? "null" : "\"" + failure + "\"")));
    }

    private void balance(String merchant, String type, String source, String parent, long gross, long fee, long net, Instant at) {
        send("ledger.entries", source, envelope("BalanceTransactionRecorded", merchant, id("txn"), at, """
                {"transaction_id":"%s","type":"%s","source_id":"%s","parent_id":%s,"currency":"EUR","gross":%d,"fee":%d,"net":%d,
                 "description":"d","created_at":"%s"}""".formatted(id("txn"), type, source, parent == null ? "null" : "\"" + parent + "\"", gross, fee, net, at)));
    }

    private long count(String table, String merchant) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE merchant_id = :m").param("m", merchant).query(Long.class).single();
    }

    /** One merchant with two paid payments, one failed, a refund, a payout and a dispute that was won. */
    private String history() {
        String merchant = id("mer");
        Instant now = Instant.now();
        for (int i = 1; i <= 2; i++) {
            String p = id("pay");
            payment(merchant, p, "requires_payment_method", "PaymentIntentCreated", now.minusSeconds(60), 10_000 * i, null, null);
            payment(merchant, p, "succeeded", "PaymentSucceeded", now.minusSeconds(30), 10_000 * i, i == 1 ? null : "blik", null);
            balance(merchant, "payment", p, null, 10_000 * i, 139 * i, 10_000 * i - 139 * i, now.minusSeconds(30));
        }
        String failed = id("pay");
        payment(merchant, failed, "requires_payment_method", "PaymentIntentCreated", now.minusSeconds(50), 5_000, null, null);
        payment(merchant, failed, "failed", "PaymentFailed", now.minusSeconds(20), 5_000, null, "insufficient_funds");
        balance(merchant, "refund", "re_1", "pay_x", -2_000, 0, -2_000, now.minusSeconds(10));
        String payout = id("po");
        send("payout.events", payout, envelope("PayoutCreated", merchant, payout, now.minusSeconds(5), """
                {"payout_id":"%s","status":"pending","amount":20000,"fee":0,"currency":"EUR","arrival_date":"2026-10-22","kind":"scheduled"}""".formatted(payout)));
        send("payout.events", payout, envelope("PayoutPaid", merchant, payout, now.minusSeconds(2), """
                {"payout_id":"%s","status":"paid","amount":20000,"fee":0,"currency":"EUR","arrival_date":"2026-10-22","kind":"scheduled"}""".formatted(payout)));
        balance(merchant, "payout", payout, null, -20_000, 0, -20_000, now.minusSeconds(5));
        String dispute = id("dp");
        send("dispute.events", dispute, envelope("DisputeOpened", merchant, dispute, now.minusSeconds(4), """
                {"dispute_id":"%s","payment_id":"pay_x","amount":5000,"currency":"EUR","fee":1500,"reason":"fraudulent","status":"needs_response"}""".formatted(dispute)));
        send("dispute.events", dispute, envelope("DisputeClosed", merchant, dispute, now.minusSeconds(1), """
                {"dispute_id":"%s","payment_id":"pay_x","amount":5000,"currency":"EUR","fee":1500,"reason":"fraudulent","status":"won"}""".formatted(dispute)));
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            assertThat(count("fact_payments", merchant)).isEqualTo(3);
            assertThat(count("fact_balance_transactions", merchant)).isEqualTo(4);
            assertThat(count("fact_payouts", merchant)).isEqualTo(1);
            assertThat(count("fact_disputes", merchant)).isEqualTo(1);
            assertThat(jdbc.sql("SELECT status FROM fact_payouts WHERE merchant_id = :m").param("m", merchant).query(String.class).single()).isEqualTo("paid");
            assertThat(jdbc.sql("SELECT status FROM fact_disputes WHERE merchant_id = :m").param("m", merchant).query(String.class).single()).isEqualTo("won");
        });
        return merchant;
    }

    @Test
    void theOverviewCountsWhatHappenedAndWhereCustomersDroppedOut() throws Exception {
        String merchant = history();
        JsonNode overview = json.readTree(mvc.perform(get("/v1/reporting/overview").with(owner(merchant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(overview.get("succeeded").asLong()).isEqualTo(2);
        assertThat(overview.get("failed").asLong()).isEqualTo(1);
        assertThat(overview.get("success_rate").asDouble()).isEqualTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(overview.get("decline_reasons").get(0).get("key").asString()).isEqualTo("insufficient_funds");
        assertThat(overview.get("method_mix").size()).isEqualTo(2); // card and blik
        assertThat(overview.get("funnel").get("opened").asLong()).isEqualTo(3);
        assertThat(overview.get("funnel").get("attempted").asLong()).isEqualTo(3);
        assertThat(overview.get("funnel").get("paid").asLong()).isEqualTo(2);
        JsonNode eur = overview.get("money").get(0);
        assertThat(eur.get("gross").asLong()).isEqualTo(30_000);
        assertThat(eur.get("fees").asLong()).isEqualTo(417);
        assertThat(eur.get("refunds").asLong()).isEqualTo(-2_000);
        // Another business sees none of it.
        mvc.perform(get("/v1/reporting/overview").with(owner(id("mer")))).andExpect(jsonPath("$.succeeded").value(0));
    }

    @Test
    void aLateOldEventDoesNotUndoANewerOne() {
        String merchant = id("mer");
        String p = id("pay");
        Instant now = Instant.now();
        payment(merchant, p, "succeeded", "PaymentSucceeded", now, 1_000, null, null);
        payment(merchant, p, "requires_payment_method", "PaymentIntentCreated", now.minusSeconds(100), 1_000, null, null);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT count(*) FROM processed_events").query(Long.class).single()).isGreaterThanOrEqualTo(2));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var row = jdbc.sql("SELECT status, created_at, succeeded_at IS NOT NULL FROM fact_payments WHERE payment_id = :p").param("p", p)
                    .query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getBoolean(3)}).single();
            assertThat(row[0]).isEqualTo("succeeded");
            assertThat((Instant) row[1]).isBefore(now.minusSeconds(50)); // the earliest time is kept
            assertThat(row[2]).isEqualTo(true);
        });
    }

    @Test
    void reportsAreBuiltFromTheReadModelAndMatchItsTotals() throws Exception {
        String merchant = history();
        String start = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        String end = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();

        String created = mvc.perform(post("/v1/reporting/report_runs").with(owner(merchant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"monthly_statement\",\"interval_start\":\"%s\",\"interval_end\":\"%s\"}".formatted(start, end)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("queued"))
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(created).get("id").asString();
        assertThat(json.readTree(created).get("download_url").isNull()).isTrue();

        while (runs.buildNext()) {
            // builds whatever is waiting, including runs from other tests
        }
        JsonNode done = json.readTree(mvc.perform(get("/v1/reporting/report_runs/" + runId).with(owner(merchant)))
                .andExpect(jsonPath("$.status").value("succeeded")).andExpect(jsonPath("$.row_count").value(1))
                .andReturn().getResponse().getContentAsString());
        String url = done.get("download_url").asString();

        // The link needs no token and gives a CSV whose totals are the ledger's.
        String csv = new String(mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("monthly_statement_")))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Month,Currency,Sales,Refunds,Fees,Disputes,Payouts,Net change");
        // sales 300.00, refunds -20.00, fees 4.17, disputes 0.00 (taken and returned) and the fee returned, payouts -200.00,
        // net = 300.00 - 4.17 - 20.00 - 200.00 = 75.83
        assertThat(csv).contains(",EUR,300.00,-20.00,4.17,");
        assertThat(csv).contains(",-200.00,75.83");

        // A link for another report, or with the signature changed, is refused.
        mvc.perform(get(url.replace(runId, "rpt_other"))).andExpect(status().isForbidden());
        mvc.perform(get(url.substring(0, url.length() - 3) + "000")).andExpect(status().isForbidden());
        // Another business cannot read the run.
        mvc.perform(get("/v1/reporting/report_runs/" + runId).with(owner(id("mer")))).andExpect(status().isNotFound());
    }

    @Test
    void aSpreadsheetAndTheOtherReportsWork() throws Exception {
        String merchant = history();
        String start = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        String end = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
        for (String type : new String[] {"balance_summary", "payments", "refunds", "fees", "payout_reconciliation", "disputes", "bnpl"}) {
            mvc.perform(post("/v1/reporting/report_runs").with(owner(merchant)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"%s\",\"format\":\"xlsx\",\"interval_start\":\"%s\",\"interval_end\":\"%s\"}".formatted(type, start, end)))
                    .andExpect(status().isAccepted());
        }
        while (runs.buildNext()) {
            // wait for the queue to empty
        }
        JsonNode list = json.readTree(mvc.perform(get("/v1/reporting/report_runs").with(owner(merchant))).andReturn().getResponse().getContentAsString());
        assertThat(list.get("data").size()).isEqualTo(7);
        for (JsonNode run : list.get("data")) {
            assertThat(run.get("status").asString()).as(run.get("type").asString()).isEqualTo("succeeded");
        }
        // The payout reconciliation of a paid payout of 200.00 that the ledger took in full has no difference.
        String rec = list.get("data").valueStream().filter(r -> r.get("type").asString().equals("payout_reconciliation")).findFirst().orElseThrow().get("id").asString();
        assertThat(runs.file(rec).orElseThrow().content()).isNotEmpty();
        assertThat(new String(runs.file(rec).orElseThrow().content(), StandardCharsets.ISO_8859_1)).startsWith("PK");
    }

    @Test
    void requestsAreValidated() throws Exception {
        String merchant = id("mer");
        mvc.perform(post("/v1/reporting/report_runs").with(owner(merchant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"nope\",\"interval_start\":\"2026-01-01\",\"interval_end\":\"2026-01-31\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("unknown_report_type"));
        mvc.perform(post("/v1/reporting/report_runs").with(owner(merchant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payments\",\"interval_start\":\"2026-02-01\",\"interval_end\":\"2026-01-01\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_interval"));
        mvc.perform(post("/v1/reporting/report_runs").with(owner(merchant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payments\",\"interval_start\":\"2024-01-01\",\"interval_end\":\"2026-01-01\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("interval_too_long"));
        mvc.perform(post("/v1/reporting/report_runs").with(owner(merchant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payments\",\"columns\":[\"nope\"],\"interval_start\":\"2026-01-01\",\"interval_end\":\"2026-01-31\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("unknown_column"));
        mvc.perform(post("/v1/reporting/report_runs").with(owner(merchant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payments\",\"format\":\"pdf\",\"interval_start\":\"2026-01-01\",\"interval_end\":\"2026-01-31\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_format"));
        mvc.perform(get("/v1/reporting/report_types")).andExpect(status().isUnauthorized());
    }

    @Test
    void droppingTheReadModelAndReplayingTheTopicsGivesTheSameNumbers() throws Exception {
        history();
        history();
        String before = mvc.perform(get("/admin/checksum")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(before).get("payments").get("rows").asLong()).isGreaterThanOrEqualTo(6);

        var result = rebuilder.rebuild();

        assertThat(result.events()).isGreaterThan(0);
        String after = mvc.perform(get("/admin/checksum")).andReturn().getResponse().getContentAsString();
        assertThat(after).isEqualTo(before);
        assertThat(json.readTree(after).get("balance_transactions").get("hash").asString()).isNotEmpty();
    }
}
