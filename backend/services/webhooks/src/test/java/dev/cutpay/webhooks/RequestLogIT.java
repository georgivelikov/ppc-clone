package dev.cutpay.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cutpay.web.SimulatedClock;
import dev.cutpay.webhooks.logs.RequestLogService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

@SpringBootTest(properties = {"cutpay.outbox.poll-interval=PT1H", "cutpay.webhooks.poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class RequestLogIT {

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
    Clock clock;

    @Autowired
    RequestLogService logs;

    @Autowired
    KafkaTemplate<String, String> kafka;

    private String merchant;

    @BeforeEach
    void freshMerchant() {
        merchant = "mer_" + UUID.randomUUID().toString().replace("-", "");
    }

    private RequestPostProcessor asMerchant() {
        return jwt().jwt(j -> j.claim("mid", merchant).claim("role", "owner"));
    }

    private void log(String method, String path, int status, boolean livemode, Instant at, String actor) {
        logs.ingest("evt_" + UUID.randomUUID(), merchant, livemode, at, json.readTree("""
                {"method":"%s","path":"%s","status":%d,"latency_ms":12,"actor":"%s","actor_type":"api_key",
                 "idempotency_key":null,"request_id":"req_1"}""".formatted(method, path, status, actor)));
    }

    @Test
    void entriesAreListedNewestFirstAndFilteredByStatusMethodPathAndActor() throws Exception {
        Instant t = clock.instant();
        log("POST", "/v1/payment_intents", 201, false, t.minusSeconds(30), "key_a");
        log("GET", "/v1/payment_intents/pay_1", 404, false, t.minusSeconds(20), "key_a");
        log("POST", "/v1/refunds", 400, false, t.minusSeconds(10), "key_b");

        mvc.perform(get("/v1/logs").with(asMerchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].path").value("/v1/refunds"))
                .andExpect(jsonPath("$.data[2].path").value("/v1/payment_intents"))
                .andExpect(jsonPath("$.data[0].actor_type").value("api_key"))
                .andExpect(jsonPath("$.data[0].latency_ms").value(12));
        mvc.perform(get("/v1/logs").param("status", "4xx").with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/v1/logs").param("status", "404").with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/v1/logs").param("method", "post").with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/v1/logs").param("path", "/v1/payment_intents").with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/v1/logs").param("actor", "key_b").with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/v1/logs").param("status", "teapot").with(asMerchant()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thePathFilterTreatsPercentAndUnderscoreLiterally() throws Exception {
        log("GET", "/v1/payouts", 200, false, clock.instant(), "key_a");
        mvc.perform(get("/v1/logs").param("path", "/v1/%").with(asMerchant()))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/v1/logs").param("path", "/v1/pay_uts").with(asMerchant()))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void paginationUsesTheLastIdAsCursor() throws Exception {
        Instant t = clock.instant();
        for (int i = 0; i < 3; i++) {
            log("GET", "/v1/balance", 200, false, t.minusSeconds(30 - i), "key_a");
        }
        JsonNode first = json.readTree(mvc.perform(get("/v1/logs").param("limit", "2").with(asMerchant()))
                .andExpect(jsonPath("$.has_more").value(true)).andReturn().getResponse().getContentAsString());
        String cursor = first.get("data").get(1).get("id").asString();
        mvc.perform(get("/v1/logs").param("starting_after", cursor).with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.has_more").value(false));
    }

    @Test
    void anEntryCanBeFetchedById() throws Exception {
        log("GET", "/v1/balance", 200, false, clock.instant(), "key_a");
        String id = json.readTree(mvc.perform(get("/v1/logs").with(asMerchant())).andReturn().getResponse()
                .getContentAsString()).get("data").get(0).get("id").asString();
        mvc.perform(get("/v1/logs/" + id).with(asMerchant())).andExpect(jsonPath("$.path").value("/v1/balance"));
    }

    @Test
    void testAndLiveEntriesAndMerchantsAreSeparate() throws Exception {
        log("GET", "/v1/balance", 200, false, clock.instant(), "key_a");
        log("GET", "/v1/payouts", 200, true, clock.instant(), "key_live");
        RequestPostProcessor liveKey = jwt().jwt(j -> j.claim("mid", merchant).claim("role", "api")
                .claim("livemode", true));

        mvc.perform(get("/v1/logs").param("livemode", "true").with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].actor").value("key_live"));
        mvc.perform(get("/v1/logs").param("livemode", "false").with(liveKey))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].livemode").value(true));
        mvc.perform(get("/v1/logs").with(jwt().jwt(j -> j.claim("mid", "mer_other").claim("role", "owner"))))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/v1/logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void theSameKafkaMessageIsStoredOnce() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        Instant at = clock.instant();
        String payload = """
                {"method":"GET","path":"/v1/balance","status":200,"latency_ms":5,"actor":"key_k","actor_type":"api_key"}""";
        logs.ingest(eventId, merchant, false, at, json.readTree(payload));
        logs.ingest(eventId, merchant, false, at, json.readTree(payload));
        mvc.perform(get("/v1/logs").with(asMerchant())).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void entriesOlderThanThirtyDaysAreDeleted() throws Exception {
        Instant now = clock.instant();
        log("GET", "/v1/old", 200, false, now.minus(Duration.ofDays(29)), "key_a");
        ((SimulatedClock) clock).advance(Duration.ofDays(2)); // the entry is now 31 days old
        log("GET", "/v1/new", 200, false, clock.instant(), "key_a");

        assertThat(logs.deleteExpired()).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/v1/logs").with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].path").value("/v1/new"));
    }

    @Test
    void anEventFromTheGatewayOnKafkaIsStored() throws Exception {
        String envelope = """
                {"event_id":"evt_%s","event_type":"ApiRequestLogged","occurred_at":"%s","aggregate_id":"req_9",
                 "merchant_id":"%s","livemode":false,"version":1,
                 "payload":{"method":"POST","path":"/v1/refunds","status":201,"latency_ms":40,"actor":"key_9",
                            "actor_type":"api_key","idempotency_key":"idem-9","request_id":"req_9"}}"""
                .formatted(UUID.randomUUID(), clock.instant(), merchant);
        kafka.send("api.requests", merchant, envelope).get();

        long deadline = System.currentTimeMillis() + 30_000;
        boolean found = false;
        while (!found && System.currentTimeMillis() < deadline) {
            String body = mvc.perform(get("/v1/logs").with(asMerchant())).andReturn().getResponse().getContentAsString();
            found = json.readTree(body).get("data").size() == 1;
            if (!found) {
                Thread.sleep(200);
            }
        }
        assertThat(found).isTrue();
        mvc.perform(get("/v1/logs").with(asMerchant()))
                .andExpect(jsonPath("$.data[0].idempotency_key").value("idem-9"))
                .andExpect(jsonPath("$.data[0].request_id").value("req_9"));
    }
}
