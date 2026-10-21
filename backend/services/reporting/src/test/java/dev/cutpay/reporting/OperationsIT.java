package dev.cutpay.reporting;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
import dev.cutpay.reporting.ops.Reconciliation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The nightly reconciliation finds what each kind of fault looks like, and dead letters can be sent back or discarded. */
@SpringBootTest(properties = {"cutpay.reconciliation.settle-wait=PT0S", "cutpay.reporting.job-poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class OperationsIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static final WireMockServer ACQUIRER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void start() {
        ACQUIRER.start();
    }

    @AfterAll
    static void stop() {
        ACQUIRER.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("cutpay.reconciliation.acquirer-url", () -> "http://localhost:" + ACQUIRER.port());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ConsumerFactory<String, String> consumers;

    private static final RequestPostProcessor ADMIN = jwt().jwt(j -> j.claim("realm", "staff").claim("role", "staff_admin")
            .claim("email", "admin@cutpay.test"));
    private static final RequestPostProcessor REVIEWER = jwt().jwt(j -> j.claim("realm", "staff").claim("role", "staff_reviewer")
            .claim("email", "reviewer@cutpay.test"));
    private static final RequestPostProcessor MERCHANT = jwt().jwt(j -> j.claim("mid", "mer_1").claim("role", "owner"));

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE fact_payments, fact_balance_transactions").update();
    }

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void payment(String paymentId, String status, long amount, String method) {
        jdbc.sql("""
                        INSERT INTO fact_payments (payment_id, merchant_id, livemode, currency, amount, status, method_type, created_at,
                                                   attempted_at, succeeded_at, last_event_at)
                        VALUES (:id, 'mer_1', false, 'EUR', :amount, :status, :method, now(), now(), now(), now())
                        """)
                .param("id", paymentId).param("amount", amount).param("status", status).param("method", method).update();
    }

    private void ledger(String type, String source, String parent, long gross) {
        jdbc.sql("""
                        INSERT INTO fact_balance_transactions (transaction_id, merchant_id, livemode, currency, type, source_id, parent_id,
                                                               gross, fee, net, created_at)
                        VALUES (:id, 'mer_1', false, 'EUR', :type, :source, :parent, :gross, 0, :gross, now())
                        """)
                .param("id", id("txn")).param("type", type).param("source", source).param("parent", parent, java.sql.Types.VARCHAR)
                .param("gross", gross).update();
    }

    private void acquirerFile(String... lines) {
        ACQUIRER.stubFor(WireMock.get(urlPathEqualTo("/settlement")).willReturn(aResponse()
                .withHeader("Content-Type", "text/csv")
                .withBody("type,acquirer_ref,reference,amount,currency,at\n" + String.join("\n", lines) + "\n")));
    }

    private static String charge(String reference, long amount) {
        return "charge,auth_" + reference + "," + reference + "," + amount + ",EUR," + Instant.now();
    }

    private static String refund(String reference, long amount) {
        return "refund,auth_" + reference + "," + reference + "," + amount + ",EUR," + Instant.now();
    }

    @Test
    void threeAccountsThatAgreeAreClean() throws Exception {
        String p = id("pay");
        String bnpl = id("pay");
        payment(p, "partially_refunded", 5000, "card");
        ledger("payment", p, null, 5000);
        ledger("refund", id("ref"), p, -1000);
        payment(bnpl, "succeeded", 9000, "bnpl"); // financed by a provider: the acquirer never sees it
        ledger("payment", bnpl, null, 9000);
        acquirerFile(charge(p, 5000), refund(p, 1000));

        Reconciliation.Run run = runAll();

        assertThat(run.status()).isEqualTo("clean");
        assertThat(run.paymentsChecked()).isEqualTo(2);
    }

    @Test
    void eachKindOfFaultIsNamed() throws Exception {
        String missingLedger = id("pay");
        String wrongAcquirer = id("pay");
        String wrongLedger = id("pay");
        String neverBooked = id("pay");
        String failed = id("pay");
        String refundGap = id("pay");
        String orphan = id("pay");
        payment(missingLedger, "succeeded", 1000, "card");

        payment(wrongAcquirer, "succeeded", 2000, "card");
        ledger("payment", wrongAcquirer, null, 2000);

        payment(wrongLedger, "succeeded", 3000, "card");
        ledger("payment", wrongLedger, null, 2999);

        payment(neverBooked, "succeeded", 4000, "card");
        ledger("payment", neverBooked, null, 4000);

        payment(failed, "failed", 5000, "card");

        payment(refundGap, "refunded", 6000, "card");
        ledger("payment", refundGap, null, 6000);
        ledger("refund", id("ref"), refundGap, -6000);

        acquirerFile(charge(missingLedger, 1000), charge(wrongAcquirer, 1999), charge(wrongLedger, 3000),
                charge(failed, 5000), charge(refundGap, 6000), refund(refundGap, 2000), charge(orphan, 700));

        Reconciliation.Run run = runAll();

        Set<String> found = mismatches(run).stream().map(m -> m.kind() + ":" + m.paymentId()).collect(Collectors.toSet());
        assertThat(found).contains(
                "missing_in_ledger:" + missingLedger,
                "acquirer_amount_differs:" + wrongAcquirer,
                "ledger_amount_differs:" + wrongLedger,
                "missing_at_acquirer:" + neverBooked,
                "charged_but_not_succeeded:" + failed,
                "refund_total_differs:" + refundGap,
                "unknown_payment:" + orphan);
        assertThat(run.status()).isEqualTo("mismatched");
    }

    @Test
    void staffSeeRunsButBusinessesDoNot() throws Exception {
        acquirerFile();
        mvc.perform(post("/v1/staff/reconciliation/runs").with(REVIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
        mvc.perform(get("/v1/staff/reconciliation/runs").with(REVIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").exists());
        mvc.perform(get("/v1/staff/reconciliation/runs").with(MERCHANT)).andExpect(status().isForbidden());
    }

    // --- dead letters ----------------------------------------------------------------------------------------------

    @Test
    void aDeadLetterCanBeSentBackToItsTopic() throws Exception {
        String key = id("key");
        String original = "ops.test.events";
        var record = new ProducerRecord<String, String>(original + ".dlt", 0, key, "{\"hello\":\"world\"}");
        record.headers().add("kafka_dlt-original-topic", original.getBytes(StandardCharsets.UTF_8));
        record.headers().add("kafka_dlt-exception-message", "boom".getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_type", "PaymentSucceeded".getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();

        var listed = mvc.perform(get("/v1/staff/dead_letters").with(REVIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.key=='%s')].error".formatted(key)).value("boom")).andReturn();
        long offset = com.jayway.jsonpath.JsonPath.<List<Integer>>read(listed.getResponse().getContentAsString(),
                "$.data[?(@.key=='%s')].offset".formatted(key)).get(0);
        String body = "{\"topic\":\"%s.dlt\",\"partition\":0,\"offset\":%d}".formatted(original, offset);

        // A reviewer may look but not act.
        mvc.perform(post("/v1/staff/dead_letters/redrive").with(REVIEWER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/staff/dead_letters/redrive").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(readBack(original, key)).isEqualTo("{\"hello\":\"world\"}");
        mvc.perform(get("/v1/staff/dead_letters").with(REVIEWER))
                .andExpect(jsonPath("$.data[?(@.key=='%s')]".formatted(key)).isEmpty());
        // Acting twice is refused.
        mvc.perform(post("/v1/staff/dead_letters/discard").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void aDiscardedDeadLetterLeavesTheList() throws Exception {
        String key = id("key");
        var record = new ProducerRecord<String, String>("ops.discard.events.dlt", 0, key, "{}");
        record.headers().add("kafka_dlt-original-topic", "ops.discard.events".getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
        var listed = mvc.perform(get("/v1/staff/dead_letters").with(ADMIN)).andReturn();
        long offset = com.jayway.jsonpath.JsonPath.<List<Integer>>read(listed.getResponse().getContentAsString(),
                "$.data[?(@.key=='%s')].offset".formatted(key)).get(0);

        mvc.perform(post("/v1/staff/dead_letters/discard").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"ops.discard.events.dlt\",\"partition\":0,\"offset\":%d}".formatted(offset)))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/staff/dead_letters").with(ADMIN)).andExpect(jsonPath("$.data[?(@.key=='%s')]".formatted(key)).isEmpty());
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    @Autowired
    Reconciliation reconciliation;

    private Reconciliation.Run runAll() {
        return reconciliation.run(null);
    }

    private List<Reconciliation.Mismatch> mismatches(Reconciliation.Run run) {
        return reconciliation.mismatches(run.id());
    }

    private String readBack(String topic, String key) {
        try (Consumer<String, String> consumer = consumers.createConsumer("readback-" + UUID.randomUUID(), "")) {
            var partitions = consumer.partitionsFor(topic).stream().map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            String[] found = new String[1];
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                    if (key.equals(r.key())) {
                        found[0] = r.value();
                    }
                }
                return found[0] != null;
            });
            return found[0];
        }
    }
}
