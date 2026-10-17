package dev.cutpay.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import dev.cutpay.web.SimulatedClock;
import dev.cutpay.webhooks.delivery.DeliveryService;
import dev.cutpay.webhooks.event.EventService;
import dev.cutpay.webhooks.signature.WebhookSignature;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
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

/**
 * The webhooks service against a real Postgres and Kafka, delivering to a small HTTP server that plays the
 * merchant. The delivery worker is slowed to once an hour so the tests call {@code processDue()} themselves
 * and move time with the simulated clock.
 */
@SpringBootTest(properties = {
        "cutpay.outbox.poll-interval=PT1H",
        "cutpay.webhooks.poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
@Import(WebhooksFlowIT.Topics.class)
class WebhooksFlowIT {

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

    /** The topics are created by the services that publish to them; here nobody does, and the listener needs them. */
    @TestConfiguration
    static class Topics {
        @Bean
        NewTopic payments() {
            return TopicBuilder.name("payment.events").partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic refunds() {
            return TopicBuilder.name("refund.events").partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic payouts() {
            return TopicBuilder.name("payout.events").partitions(3).replicas(1).build();
        }
    }

    /** What a received request looked like. */
    record Received(String body, String signature, String eventId) {
    }

    /** The merchant's server: each test gets its own path, with a status it can change. */
    static final HttpServer MERCHANT = startMerchant();
    static final Map<String, Integer> STATUS = new ConcurrentHashMap<>();
    static final Map<String, List<Received>> RECEIVED = new ConcurrentHashMap<>();

    private static HttpServer startMerchant() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RECEIVED.computeIfAbsent(path, p -> new CopyOnWriteArrayList<>()).add(new Received(body,
                        exchange.getRequestHeaders().getFirst("Cutpay-Signature"),
                        exchange.getRequestHeaders().getFirst("Cutpay-Event-Id")));
                int code = STATUS.getOrDefault(path, 200);
                byte[] answer = (code == 200 ? "ok" : "broken").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, answer.length);
                exchange.getResponseBody().write(answer);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopMerchant() {
        MERCHANT.stop(0);
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
    EventService events;

    @Autowired
    DeliveryService deliveries;

    @Autowired
    KafkaTemplate<String, String> kafka;

    private String merchant;
    private String path;

    @BeforeEach
    void freshMerchant() {
        merchant = "mer_" + UUID.randomUUID().toString().replace("-", "");
        path = "/hook-" + UUID.randomUUID();
        // The database is shared by every test in the class; leftovers from an earlier one would be
        // picked up by processDue() and spoil the counts.
        jdbc.sql("UPDATE webhook_deliveries SET status = 'failed', next_attempt_at = NULL WHERE status = 'pending'")
                .update();
    }

    private String url() {
        return "http://localhost:" + MERCHANT.getAddress().getPort() + path;
    }

    private RequestPostProcessor asMerchant() {
        return jwt().jwt(j -> j.claim("mid", merchant).claim("role", "owner"));
    }

    private RequestPostProcessor asKey(boolean livemode) {
        return jwt().jwt(j -> j.claim("mid", merchant).claim("role", "api").claim("livemode", livemode));
    }

    private void advance(Duration amount) {
        ((SimulatedClock) clock).advance(amount);
    }

    private JsonNode createEndpoint(String body) throws Exception {
        return json.readTree(mvc.perform(post("/v1/webhook_endpoints").with(asMerchant())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String createEndpoint(String pathSuffix, String events) throws Exception {
        return createEndpoint("{\"url\":\"" + url() + pathSuffix + "\",\"enabled_events\":" + events + "}")
                .get("id").asString();
    }

    private void paymentSucceeded(String paymentId) {
        events.ingest("evt_" + UUID.randomUUID(), "PaymentSucceeded", merchant, false, json.readTree("""
                {"payment_id":"%s","status":"succeeded","amount":10000,"currency":"EUR","card_brand":"visa"}"""
                .formatted(paymentId)));
    }

    private JsonNode deliveriesOf(String endpointId) throws Exception {
        return json.readTree(mvc.perform(get("/v1/webhook_deliveries").param("endpoint", endpointId)
                        .with(asMerchant()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private boolean failing(String endpointId) {
        return jdbc.sql("SELECT failing_since IS NOT NULL FROM webhook_endpoints WHERE id = :id")
                .param("id", endpointId).query(Boolean.class).single();
    }

    private List<Received> received(String suffix) {
        return RECEIVED.getOrDefault(path + suffix, List.of());
    }

    // --- endpoints --------------------------------------------------------------------------------

    @Test
    void creatingAnEndpointRevealsTheSecretOnlyOnce() throws Exception {
        JsonNode created = createEndpoint("{\"url\":\"" + url() + "\",\"description\":\"Shop\"}");
        assertThat(created.get("secret").asString()).matches("whsec_[0-9A-Za-z]{32}");
        assertThat(created.get("enabled_events").get(0).asString()).isEqualTo("*");
        assertThat(created.get("status").asString()).isEqualTo("enabled");

        mvc.perform(get("/v1/webhook_endpoints/" + created.get("id").asString()).with(asMerchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").doesNotExist());
        mvc.perform(get("/v1/webhook_endpoints").with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].secret").doesNotExist());
    }

    @Test
    void invalidEndpointsAreRejected() throws Exception {
        mvc.perform(post("/v1/webhook_endpoints").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://example.com\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_url"));
        mvc.perform(post("/v1/webhook_endpoints").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url() + "\",\"enabled_events\":[\"payout.exploded\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_event_type"));
        mvc.perform(post("/v1/webhook_endpoints").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url() + "\",\"enabled_events\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("events_required"));
        // Live mode needs https, and an API key's own mode decides which mode it is.
        mvc.perform(post("/v1/webhook_endpoints").with(asKey(true)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://shop.example.com/hook\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_url"));
    }

    @Test
    void viewersCannotChangeEndpoints() throws Exception {
        mvc.perform(post("/v1/webhook_endpoints").with(jwt().jwt(j -> j.claim("mid", merchant).claim("role", "viewer")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void endpointsCanBeUpdatedDisabledAndDeleted() throws Exception {
        String id = createEndpoint("", "[\"*\"]");
        mvc.perform(patch("/v1/webhook_endpoints/" + id).with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled_events\":[\"payout.*\"],\"description\":\"Payouts only\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled_events[0]").value("payout.*"))
                .andExpect(jsonPath("$.description").value("Payouts only"));
        mvc.perform(patch("/v1/webhook_endpoints/" + id).with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"disabled\"}"))
                .andExpect(jsonPath("$.status").value("disabled"))
                .andExpect(jsonPath("$.disabled_reason").value("Disabled by you"));
        mvc.perform(patch("/v1/webhook_endpoints/" + id).with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"enabled\"}"))
                .andExpect(jsonPath("$.status").value("enabled"))
                .andExpect(jsonPath("$.disabled_reason").doesNotExist());
        mvc.perform(delete("/v1/webhook_endpoints/" + id).with(asMerchant())).andExpect(status().isOk());
        mvc.perform(get("/v1/webhook_endpoints/" + id).with(asMerchant())).andExpect(status().isNotFound());
        mvc.perform(get("/v1/webhook_endpoints").with(asMerchant())).andExpect(jsonPath("$.data").isEmpty());
    }

    // --- delivery ---------------------------------------------------------------------------------

    @Test
    void anEventIsSignedAndSentOnlyToEndpointsSubscribedToIt() throws Exception {
        JsonNode all = createEndpoint("{\"url\":\"" + url() + "/all\"}");
        createEndpoint("/payouts", "[\"payout.*\"]");

        paymentSucceeded("pay_1");
        assertThat(deliveries.processDue()).isEqualTo(1);

        assertThat(received("/payouts")).isEmpty();
        assertThat(received("/all")).hasSize(1);
        Received request = received("/all").get(0);
        JsonNode body = json.readTree(request.body());
        assertThat(body.get("type").asString()).isEqualTo("payment_intent.succeeded");
        assertThat(body.get("object").asString()).isEqualTo("event");
        assertThat(body.get("livemode").asBoolean()).isFalse();
        assertThat(body.get("data").get("object").get("id").asString()).isEqualTo("pay_1");
        assertThat(request.eventId()).isEqualTo(body.get("id").asString());
        assertThat(WebhookSignature.verify(all.get("secret").asString(), request.body(), request.signature(),
                clock.instant())).isEqualTo(WebhookSignature.Result.VALID);
        // Anyone without the endpoint secret cannot produce a valid signature.
        assertThat(WebhookSignature.verify("whsec_wrong", request.body(), request.signature(), clock.instant()))
                .isEqualTo(WebhookSignature.Result.NO_MATCHING_SIGNATURE);

        JsonNode delivery = deliveriesOf(all.get("id").asString()).get(0);
        assertThat(delivery.get("status").asString()).isEqualTo("succeeded");
        assertThat(delivery.get("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void theSameInternalEventCreatesOneEventAndOneDelivery() throws Exception {
        String id = createEndpoint("", "[\"*\"]");
        String sourceEvent = "evt_" + UUID.randomUUID();
        JsonNode payload = json.readTree("""
                {"payment_id":"pay_9","status":"succeeded","amount":500,"currency":"EUR"}""");
        events.ingest(sourceEvent, "PaymentSucceeded", merchant, false, payload);
        events.ingest(sourceEvent, "PaymentSucceeded", merchant, false, payload);

        assertThat(deliveriesOf(id)).hasSize(1);
        assertThat(deliveries.processDue()).isEqualTo(1);
        assertThat(received("")).hasSize(1);
    }

    @Test
    void aFailingEndpointIsRetriedOnTheScheduleUntilItRecovers() throws Exception {
        STATUS.put(path, 500);
        String id = createEndpoint("", "[\"*\"]");
        paymentSucceeded("pay_2");

        assertThat(deliveries.processDue()).isEqualTo(1); // attempt 1 fails
        assertThat(deliveriesOf(id).get(0).get("status").asString()).isEqualTo("pending");

        // Nothing is due until a minute has passed.
        assertThat(deliveries.processDue()).isZero();
        advance(Duration.ofSeconds(59));
        assertThat(deliveries.processDue()).isZero();
        advance(Duration.ofSeconds(1));
        assertThat(deliveries.processDue()).isEqualTo(1); // attempt 2 fails, next in 5 minutes

        advance(Duration.ofMinutes(4));
        assertThat(deliveries.processDue()).isZero();
        advance(Duration.ofMinutes(1));
        STATUS.put(path, 200); // the merchant fixes their server
        assertThat(deliveries.processDue()).isEqualTo(1); // attempt 3 succeeds

        JsonNode delivery = deliveriesOf(id).get(0);
        assertThat(delivery.get("status").asString()).isEqualTo("succeeded");
        assertThat(delivery.get("attempts").asInt()).isEqualTo(3);
        assertThat(received("")).hasSize(3);

        // Every attempt is logged with what the merchant's server answered.
        JsonNode detail = json.readTree(mvc.perform(get("/v1/webhook_deliveries/" + delivery.get("id").asString())
                        .with(asMerchant())).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("attempt_log")).hasSize(3);
        assertThat(detail.get("attempt_log").get(0).get("response_code").asInt()).isEqualTo(500);
        assertThat(detail.get("attempt_log").get(0).get("response_body_snippet").asString()).isEqualTo("broken");
        assertThat(detail.get("attempt_log").get(2).get("response_code").asInt()).isEqualTo(200);
        // The body and event id are the same on every retry, so the receiver can deduplicate.
        assertThat(received("").stream().map(Received::body).distinct()).hasSize(1);
    }

    @Test
    void anUnreachableEndpointCountsAsAFailure() throws Exception {
        String id = createEndpoint("{\"url\":\"http://localhost:1/unreachable\"}").get("id").asString();
        paymentSucceeded("pay_3");
        assertThat(deliveries.processDue()).isEqualTo(1);
        JsonNode delivery = json.readTree(mvc.perform(get("/v1/webhook_deliveries/"
                        + deliveriesOf(id).get(0).get("id").asString()).with(asMerchant()))
                .andReturn().getResponse().getContentAsString());
        assertThat(delivery.get("status").asString()).isEqualTo("pending");
        assertThat(delivery.get("attempt_log").get(0).get("response_code").isNull()).isTrue();
        assertThat(delivery.get("attempt_log").get(0).get("error").asString()).isNotBlank();
    }

    @Test
    void retriesStopAfterThreeDaysAndALaterFailureDisablesTheEndpoint() throws Exception {
        STATUS.put(path, 500);
        String id = createEndpoint("", "[\"*\"]");
        paymentSucceeded("pay_4");

        int attempts = 0;
        while (deliveries.processDue() > 0 || "pending".equals(deliveriesOf(id).get(0).get("status").asString())) {
            attempts++;
            advance(Duration.ofHours(10)); // longer than every step of the schedule
            assertThat(attempts).isLessThan(30);
        }
        JsonNode delivery = deliveriesOf(id).get(0);
        assertThat(delivery.get("status").asString()).isEqualTo("failed");
        assertThat(delivery.get("attempts").asInt()).isBetween(6, 12);

        // The endpoint has now been failing for about three days; one more failed event disables it.
        advance(Duration.ofHours(10));
        paymentSucceeded("pay_5");
        assertThat(deliveries.processDue()).isEqualTo(1);
        mvc.perform(get("/v1/webhook_endpoints/" + id).with(asMerchant()))
                .andExpect(jsonPath("$.status").value("disabled"))
                .andExpect(jsonPath("$.disabled_reason").value(org.hamcrest.Matchers.containsString("automatically")));

        // A disabled endpoint gets nothing new.
        int before = received("").size();
        paymentSucceeded("pay_6");
        assertThat(deliveries.processDue()).isZero();
        assertThat(received("")).hasSize(before);
    }

    @Test
    void aSuccessfulDeliveryEndsTheFailureStreak() throws Exception {
        STATUS.put(path, 500);
        String id = createEndpoint("", "[\"*\"]");
        paymentSucceeded("pay_7");
        deliveries.processDue();
        assertThat(failing(id)).isTrue();

        STATUS.put(path, 200);
        advance(Duration.ofMinutes(1));
        deliveries.processDue();
        assertThat(failing(id)).isFalse();
    }

    @Test
    void aDeliveryCanBeResentByHand() throws Exception {
        STATUS.put(path, 500);
        String id = createEndpoint("", "[\"*\"]");
        paymentSucceeded("pay_8");
        deliveries.processDue();
        String deliveryId = deliveriesOf(id).get(0).get("id").asString();

        STATUS.put(path, 200);
        mvc.perform(post("/v1/webhook_deliveries/" + deliveryId + "/retry").with(asMerchant()))
                .andExpect(status().isAccepted());
        assertThat(deliveries.processDue()).isEqualTo(1); // no need to wait for the schedule

        mvc.perform(get("/v1/webhook_deliveries/" + deliveryId).with(asMerchant()))
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.attempts").value(2));

        // Even a delivered event can be sent again.
        mvc.perform(post("/v1/webhook_deliveries/" + deliveryId + "/retry").with(asMerchant()))
                .andExpect(status().isAccepted());
        assertThat(deliveries.processDue()).isEqualTo(1);
        assertThat(received("")).hasSize(3);
    }

    @Test
    void resendingToADisabledEndpointIsRefused() throws Exception {
        String id = createEndpoint("", "[\"*\"]");
        paymentSucceeded("pay_10");
        deliveries.processDue();
        String deliveryId = deliveriesOf(id).get(0).get("id").asString();
        mvc.perform(patch("/v1/webhook_endpoints/" + id).with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"disabled\"}"));
        mvc.perform(post("/v1/webhook_deliveries/" + deliveryId + "/retry").with(asMerchant()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("endpoint_disabled"));
    }

    @Test
    void aTestEventGoesToThatEndpointOnly() throws Exception {
        String id = createEndpoint("/one", "[\"payout.paid\"]");
        createEndpoint("/two", "[\"*\"]");

        mvc.perform(post("/v1/webhook_endpoints/" + id + "/test").with(asMerchant()))
                .andExpect(status().isCreated());
        assertThat(deliveries.processDue()).isEqualTo(1);

        // Sent even though the endpoint is not subscribed to test events, and not to the other endpoint.
        assertThat(received("/two")).isEmpty();
        assertThat(received("/one")).hasSize(1);
        assertThat(json.readTree(received("/one").get(0).body()).get("type").asString())
                .isEqualTo("webhook_endpoint.test");
    }

    // --- events API, isolation --------------------------------------------------------------------

    @Test
    void eventsAreListedAndFetchedAsSent() throws Exception {
        createEndpoint("", "[\"*\"]");
        paymentSucceeded("pay_11");
        paymentSucceeded("pay_12");

        JsonNode list = json.readTree(mvc.perform(get("/v1/events").with(asMerchant()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString());
        String newest = list.get("data").get(0).get("id").asString();
        assertThat(list.get("data").get(0).get("data").get("object").get("id").asString()).isEqualTo("pay_12");

        mvc.perform(get("/v1/events/" + newest).with(asMerchant()))
                .andExpect(jsonPath("$.type").value("payment_intent.succeeded"));
        mvc.perform(get("/v1/events").param("limit", "1").with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.has_more").value(true));
        mvc.perform(get("/v1/events").param("starting_after", newest).with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].data.object.id").value("pay_11"));
        mvc.perform(get("/v1/events").param("type", "payout.paid").with(asMerchant()))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void testAndLiveDataNeverMix() throws Exception {
        String testEndpoint = createEndpoint("/test-mode", "[\"*\"]");
        events.ingest("evt_" + UUID.randomUUID(), "PayoutPaid", merchant, true, json.readTree("""
                {"payout_id":"po_1","status":"paid","amount":100,"fee":0,"currency":"EUR"}"""));
        paymentSucceeded("pay_13");

        // A live key sees no test endpoints or events; a test key sees only test ones.
        mvc.perform(get("/v1/webhook_endpoints").param("livemode", "false").with(asKey(true)))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/v1/webhook_endpoints/" + testEndpoint).with(asKey(true))).andExpect(status().isNotFound());
        mvc.perform(get("/v1/events").with(asKey(true))).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("payout.paid"))
                .andExpect(jsonPath("$.data[0].livemode").value(true));
        mvc.perform(get("/v1/events").param("livemode", "true").with(asKey(false)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("payment_intent.succeeded"));

        // The live event had no live endpoint to go to, and the test endpoint did not get it.
        assertThat(deliveries.processDue()).isEqualTo(1);
        assertThat(received("/test-mode")).hasSize(1);
        assertThat(json.readTree(received("/test-mode").get(0).body()).get("livemode").asBoolean()).isFalse();
    }

    @Test
    void merchantsOnlySeeTheirOwnWebhooks() throws Exception {
        String id = createEndpoint("", "[\"*\"]");
        paymentSucceeded("pay_14");
        deliveries.processDue();
        RequestPostProcessor other = jwt().jwt(j -> j.claim("mid", "mer_other").claim("role", "owner"));

        mvc.perform(get("/v1/webhook_endpoints/" + id).with(other)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/webhook_endpoints").with(other)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/v1/webhook_deliveries").with(other)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/v1/events").with(other)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(delete("/v1/webhook_endpoints/" + id).with(other)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/events")).andExpect(status().isUnauthorized());
    }

    // --- Kafka ------------------------------------------------------------------------------------

    @Test
    void aPaymentEventOnKafkaReachesTheMerchant() throws Exception {
        createEndpoint("", "[\"payment_intent.succeeded\"]");
        String envelope = """
                {"event_id":"evt_%s","event_type":"PaymentSucceeded","occurred_at":"2026-10-17T10:00:00Z",
                 "aggregate_id":"pay_kafka","merchant_id":"%s","livemode":false,"version":1,
                 "payload":{"payment_id":"pay_kafka","status":"succeeded","amount":2500,"currency":"EUR"}}"""
                .formatted(UUID.randomUUID(), merchant);
        kafka.send("payment.events", "pay_kafka", envelope).get();

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (deliveries.processDue() > 0 && !received("").isEmpty()) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(received("")).hasSize(1);
        assertThat(json.readTree(received("").get(0).body()).get("data").get("object").get("id").asString())
                .isEqualTo("pay_kafka");
    }
}
