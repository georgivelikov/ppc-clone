package dev.cutpay.links;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.cutpay.links.link.LinkService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Links against a real Postgres and Kafka, with the payments service replaced by a stub. The housekeeping job
 * is slowed to once an hour so the tests call the same service methods themselves.
 */
@SpringBootTest(properties = {
        "cutpay.outbox.poll-interval=PT0.2S",
        "cutpay.links.job-poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class LinksFlowIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static final WireMockServer PAYMENTS = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void startStub() {
        PAYMENTS.start();
    }

    @AfterAll
    static void stopStub() {
        PAYMENTS.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("cutpay.links.payments-url", () -> "http://localhost:" + PAYMENTS.port());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    LinkService links;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    private String merchant;

    @BeforeEach
    void freshMerchantAndHealthyPayments() {
        merchant = "mer_" + UUID.randomUUID().toString().replace("-", "");
        PAYMENTS.resetAll();
        PAYMENTS.stubFor(WireMock.post(urlEqualTo("/internal/payments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"payment_id\":\"pay_stub\",\"client_secret\":\"pay_stub_secret_x\","
                        + "\"url\":\"http://localhost:5174/c/pay_stub_secret_x\"}")));
    }

    private RequestPostProcessor asMerchant() {
        return jwt().jwt(j -> j.claim("mid", merchant).claim("role", "owner"));
    }

    private RequestPostProcessor asLiveKey() {
        return jwt().jwt(j -> j.claim("mid", merchant).claim("role", "api").claim("livemode", true));
    }

    private JsonNode createLink(String body) throws Exception {
        String response = mvc.perform(post("/v1/payment_links").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private JsonNode fixedLink(Integer maxUses) throws Exception {
        return createLink("""
                {"type":"fixed","amount":1500,"currency":"EUR","title":"Table 4","merchant_name":"Rose Cafe"%s}"""
                .formatted(maxUses == null ? "" : ",\"max_uses\":" + maxUses));
    }

    private int openSession(String slug) throws Exception {
        return mvc.perform(post("/links/" + slug + "/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
    }

    private long count(String sql, Object... params) {
        var statement = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            statement = statement.param("p" + (i + 1), params[i]);
        }
        return statement.query(Long.class).single();
    }

    private String reservationOf(String linkId) {
        return jdbc.sql("SELECT id FROM link_reservations WHERE link_id = :p1 ORDER BY created_at DESC LIMIT 1")
                .param("p1", linkId).query(String.class).single();
    }

    // --- creating and reading --------------------------------------------------------------------

    @Test
    void aCreatedLinkIsShownToCustomersWithoutAnyMerchantDetailsAndSharedAtAShortUrl() throws Exception {
        JsonNode link = fixedLink(null);
        String slug = link.get("slug").asString();
        assertThat(slug).hasSize(10);
        assertThat(link.get("url").asString()).endsWith("/l/" + slug);
        assertThat(link.get("status").asString()).isEqualTo("active");

        mvc.perform(get("/links/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Table 4"))
                .andExpect(jsonPath("$.amount").value(1500))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.merchant_name").value("Rose Cafe"))
                .andExpect(jsonPath("$.merchant_id").doesNotExist())
                .andExpect(jsonPath("$.uses_count").doesNotExist());
        mvc.perform(get("/links/nosuchslug")).andExpect(status().isNotFound());
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'PaymentLinkCreated' AND event_key = :p1",
                link.get("id").asString())).isEqualTo(1);
    }

    @Test
    void invalidLinksAreRefusedWithAHelpfulCode() throws Exception {
        String[][] cases = {
                {"{\"type\":\"fixed\",\"currency\":\"EUR\",\"title\":\"x\"}", "invalid_amount"},
                {"{\"type\":\"nope\",\"amount\":100,\"currency\":\"EUR\",\"title\":\"x\"}", "invalid_type"},
                {"{\"type\":\"fixed\",\"amount\":100,\"currency\":\"XXX\",\"title\":\"x\"}", "unsupported_currency"},
                {"{\"type\":\"products\",\"currency\":\"EUR\",\"title\":\"x\"}", "line_items_required"},
                {"{\"type\":\"fixed\",\"amount\":100,\"currency\":\"EUR\",\"title\":\"x\",\"collect\":[\"ssn\"]}",
                        "invalid_collect"},
                {"{\"type\":\"fixed\",\"amount\":100,\"currency\":\"EUR\",\"title\":\"x\",\"after_completion\":\"redirect\"}",
                        "invalid_redirect_url"},
                {"{\"type\":\"customer_chooses\",\"min_amount\":900,\"max_amount\":100,\"currency\":\"EUR\",\"title\":\"x\"}",
                        "invalid_range"},
        };
        for (String[] c : cases) {
            mvc.perform(post("/v1/payment_links").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                            .content(c[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value(c[1]));
        }
    }

    @Test
    void linksAreInvisibleToOtherMerchantsAndToTheOtherMode() throws Exception {
        JsonNode link = fixedLink(null);
        String id = link.get("id").asString();
        String other = "mer_" + UUID.randomUUID().toString().replace("-", "");
        mvc.perform(get("/v1/payment_links/" + id).with(jwt().jwt(j -> j.claim("mid", other).claim("role", "owner"))))
                .andExpect(status().isNotFound());
        // A live API key cannot open a test link by id, and the list is separated by mode too.
        mvc.perform(get("/v1/payment_links/" + id).with(asLiveKey())).andExpect(status().isNotFound());
        mvc.perform(get("/v1/payment_links?livemode=true").with(asMerchant()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/v1/payment_links").with(asMerchant()))
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value(id));
    }

    @Test
    void aMerchantCanDeactivateAndReactivateALink() throws Exception {
        JsonNode link = fixedLink(null);
        String id = link.get("id").asString();
        mvc.perform(patch("/v1/payment_links/" + id).with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("inactive"));
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'PaymentLinkDeactivated' AND event_key = :p1", id))
                .isEqualTo(1);
        mvc.perform(get("/links/" + link.get("slug").asString()))
                .andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.unavailable_reason").value("inactive"));
        assertThat(openSession(link.get("slug").asString())).isEqualTo(409);

        mvc.perform(patch("/v1/payment_links/" + id).with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(jsonPath("$.status").value("active"));
        assertThat(openSession(link.get("slug").asString())).isEqualTo(201);
    }

    // --- sessions --------------------------------------------------------------------------------

    @Test
    void aSessionCarriesTheLinkInThePaymentsMetadataAndSendsTheCustomerBackToTheLink() throws Exception {
        JsonNode link = createLink("""
                {"type":"fixed","amount":1500,"currency":"EUR","title":"Table 4","quantity_adjustable":true,
                 "collect":["name","phone"],"metadata":{"table":"4"}}""");
        String slug = link.get("slug").asString();
        mvc.perform(post("/links/" + slug + "/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("name_required"));

        mvc.perform(post("/links/" + slug + "/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2,\"name\":\"Ana\",\"phone\":\"+359 88 123\",\"email\":\"ana@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_secret").value("pay_stub_secret_x"))
                .andExpect(jsonPath("$.url").value("http://localhost:5174/c/pay_stub_secret_x"));

        var sent = json.readTree(PAYMENTS.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        assertThat(sent.get("merchant_id").asString()).isEqualTo(merchant);
        assertThat(sent.get("livemode").asBoolean()).isFalse();
        JsonNode payment = sent.get("payment");
        assertThat(payment.get("amount").asLong()).isEqualTo(3000);
        assertThat(payment.get("customer_email").asString()).isEqualTo("ana@example.com");
        assertThat(payment.get("return_url").asString()).endsWith("/l/" + slug + "/thanks");
        JsonNode metadata = payment.get("metadata");
        assertThat(metadata.get("link_id").asString()).isEqualTo(link.get("id").asString());
        assertThat(metadata.get("link_reservation_id").asString()).startsWith("lres_");
        assertThat(metadata.get("table").asString()).isEqualTo("4");
        assertThat(metadata.get("customer_name").asString()).isEqualTo("Ana");
    }

    @Test
    void aSessionOnAProductsLinkChargesTheSumOfItsLines() throws Exception {
        JsonNode link = createLink("""
                {"type":"products","currency":"EUR","title":"Lunch","line_items":[
                  {"product_name":"Soup","unit_amount":600,"quantity":1},
                  {"product_name":"Bread","unit_amount":150,"quantity":2}]}""");
        mvc.perform(post("/links/" + link.get("slug").asString() + "/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isCreated());
        var sent = json.readTree(PAYMENTS.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        assertThat(sent.get("payment").get("amount").asLong()).isEqualTo(900);
    }

    // --- the race the plan asks for ---------------------------------------------------------------

    @Test
    void whenManyCustomersOpenALinkWithOneUseLeftExactlyOneGetsIn() throws Exception {
        JsonNode link = fixedLink(1);
        String slug = link.get("slug").asString();
        int customers = 12;

        var pool = Executors.newFixedThreadPool(customers);
        var ready = new CountDownLatch(customers);
        var go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < customers; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return openSession(slug);
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(customers - 1);
        assertThat(count("SELECT COUNT(*) FROM link_reservations WHERE link_id = :p1", link.get("id").asString()))
                .isEqualTo(1);
        assertThat(count("SELECT reserved FROM payment_links WHERE id = :p1", link.get("id").asString())).isEqualTo(1);
        assertThat(PAYMENTS.getAllServeEvents()).hasSize(1); // the losers never started a payment
        mvc.perform(post("/links/" + slug + "/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("link_unavailable"));
    }

    @Test
    void aSuccessfulPaymentUsesTheLinkUpAndClosesIt() throws Exception {
        JsonNode link = fixedLink(1);
        String id = link.get("id").asString();
        assertThat(openSession(link.get("slug").asString())).isEqualTo(201);

        String eventId = "evt_" + UUID.randomUUID();
        links.onPaymentSucceeded(eventId, id, reservationOf(id), 1500);
        links.onPaymentSucceeded(eventId, id, reservationOf(id), 1500); // redelivery changes nothing

        mvc.perform(get("/v1/payment_links/" + id).with(asMerchant()))
                .andExpect(jsonPath("$.uses_count").value(1))
                .andExpect(jsonPath("$.amount_collected").value(1500))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value("inactive"));
        assertThat(count("SELECT reserved FROM payment_links WHERE id = :p1", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'PaymentLinkDeactivated' AND event_key = :p1", id))
                .isEqualTo(1);
        mvc.perform(get("/v1/payment_links/" + id + "/payments").with(asMerchant()))
                .andExpect(jsonPath("$.data[0].payment_id").value("pay_stub"))
                .andExpect(jsonPath("$.data[0].amount").value(1500));
        assertThat(openSession(link.get("slug").asString())).isEqualTo(409);
    }

    @Test
    void aFailedPaymentGivesTheUseBackSoTheNextCustomerCanPay() throws Exception {
        JsonNode link = fixedLink(1);
        String slug = link.get("slug").asString();
        String id = link.get("id").asString();
        assertThat(openSession(slug)).isEqualTo(201);
        assertThat(openSession(slug)).isEqualTo(409);

        links.onPaymentEnded("evt_" + UUID.randomUUID(), reservationOf(id));

        assertThat(count("SELECT reserved FROM payment_links WHERE id = :p1", id)).isZero();
        assertThat(openSession(slug)).isEqualTo(201);
    }

    @Test
    void aReservationNobodyFinishesIsGivenBackWhenItTimesOut() throws Exception {
        JsonNode link = fixedLink(1);
        String slug = link.get("slug").asString();
        String id = link.get("id").asString();
        assertThat(openSession(slug)).isEqualTo(201);

        links.releaseExpiredReservations();
        assertThat(openSession(slug)).isEqualTo(409); // still within its time

        jdbc.sql("UPDATE link_reservations SET expires_at = now() - interval '1 minute' WHERE link_id = :id")
                .param("id", id).update();
        assertThat(links.releaseExpiredReservations()).isPositive();

        assertThat(count("SELECT reserved FROM payment_links WHERE id = :p1", id)).isZero();
        assertThat(openSession(slug)).isEqualTo(201);
    }

    @Test
    void ifThePaymentCannotBeStartedTheUseIsNotKept() throws Exception {
        JsonNode link = fixedLink(1);
        String slug = link.get("slug").asString();
        PAYMENTS.stubFor(WireMock.post(urlEqualTo("/internal/payments")).willReturn(aResponse().withStatus(500)));

        assertThat(openSession(slug)).isEqualTo(502);
        assertThat(count("SELECT reserved FROM payment_links WHERE id = :p1", link.get("id").asString())).isZero();

        PAYMENTS.resetAll();
        PAYMENTS.stubFor(WireMock.post(urlEqualTo("/internal/payments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"payment_id\":\"pay_2\",\"client_secret\":\"s\",\"url\":\"u\"}")));
        assertThat(openSession(slug)).isEqualTo(201);
    }

    @Test
    void aLinkWithoutALimitTakesAnyNumberOfPayments() throws Exception {
        JsonNode link = fixedLink(null);
        for (int i = 0; i < 3; i++) {
            assertThat(openSession(link.get("slug").asString())).isEqualTo(201);
        }
        assertThat(count("SELECT reserved FROM payment_links WHERE id = :p1", link.get("id").asString())).isEqualTo(3);
    }

    @Test
    void anExpiredLinkStopsTakingPaymentsAndIsClosedByTheJob() throws Exception {
        JsonNode link = fixedLink(null);
        String id = link.get("id").asString();
        jdbc.sql("UPDATE payment_links SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", id).update();

        assertThat(openSession(link.get("slug").asString())).isEqualTo(409);
        mvc.perform(get("/links/" + link.get("slug").asString())).andExpect(jsonPath("$.unavailable_reason").value("expired"));
        assertThat(links.deactivateExpiredLinks()).isPositive();
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'PaymentLinkDeactivated' AND event_key = :p1", id))
                .isEqualTo(1);
    }

    @Test
    void customerChoosesLinksEnforceTheMerchantsRange() throws Exception {
        JsonNode link = createLink("""
                {"type":"customer_chooses","min_amount":500,"max_amount":5000,"currency":"EUR","title":"Tip"}""");
        String path = "/links/" + link.get("slug").asString() + "/sessions";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("amount_required"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("amount_out_of_range"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":2500}"))
                .andExpect(status().isCreated());
        assertThat(count("SELECT reserved FROM payment_links WHERE id = :p1", link.get("id").asString())).isEqualTo(1);
    }

    // --- Kafka -----------------------------------------------------------------------------------

    @Test
    void aPaymentSucceededEventFromKafkaUsesTheLink() throws Exception {
        JsonNode link = fixedLink(2);
        String id = link.get("id").asString();
        assertThat(openSession(link.get("slug").asString())).isEqualTo(201);
        String event = """
                {"event_id":"evt_%s","event_type":"PaymentSucceeded","merchant_id":"%s","livemode":false,
                 "payload":{"payment_id":"pay_stub","status":"succeeded","amount":1500,"currency":"EUR",
                            "metadata":{"link_id":"%s","link_reservation_id":"%s"}}}"""
                .formatted(UUID.randomUUID(), merchant, id, reservationOf(id));
        kafka.send("payment.events", "pay_stub", event).get();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(
                () -> assertThat(count("SELECT uses_count FROM payment_links WHERE id = :p1", id)).isEqualTo(1));
        mvc.perform(get("/v1/payment_links/" + id).with(asMerchant())).andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void paymentsThatAreNotFromALinkAreIgnored() throws Exception {
        String event = """
                {"event_id":"evt_%s","event_type":"PaymentSucceeded","merchant_id":"%s","livemode":false,
                 "payload":{"payment_id":"pay_plain","status":"succeeded","amount":100,"currency":"EUR","metadata":{}}}"""
                .formatted(UUID.randomUUID(), merchant);
        kafka.send("payment.events", "pay_plain", event).get();
        Thread.sleep(1000);
        assertThat(count("SELECT COUNT(*) FROM processed_events WHERE event_id = :p1",
                json.readTree(event).get("event_id").asString())).isZero();
    }

    // --- QR --------------------------------------------------------------------------------------

    @Test
    void theQrCodeCanBeDownloadedInEachFormatAndTheTableCardIsAPdf() throws Exception {
        JsonNode link = fixedLink(null);
        String id = link.get("id").asString();
        mvc.perform(get("/v1/payment_links/" + id + "/qr?format=svg").with(asMerchant()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("image/svg+xml"));
        mvc.perform(get("/v1/payment_links/" + id + "/qr?format=png&size=300&fg=%23123456").with(asMerchant()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
        mvc.perform(get("/v1/payment_links/" + id + "/qr?format=gif").with(asMerchant()))
                .andExpect(status().isBadRequest());
        byte[] pdf = mvc.perform(get("/v1/payment_links/" + id + "/card.pdf").with(asMerchant()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");

        String other = "mer_" + UUID.randomUUID().toString().replace("-", "");
        mvc.perform(get("/v1/payment_links/" + id + "/qr").with(jwt().jwt(j -> j.claim("mid", other))))
                .andExpect(status().isNotFound());
    }

    @Test
    void theGenericQrEndpointOnlyEncodesCheckoutAddresses() throws Exception {
        mvc.perform(get("/v1/qr").param("data", "http://localhost:5174/c/pay_1_secret_abc").with(asMerchant()))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/qr").param("data", "https://evil.example/pay").with(asMerchant()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theLinkPagesNeedNoLoginButTheManagementRoutesDo() throws Exception {
        mvc.perform(get("/v1/payment_links")).andExpect(status().isUnauthorized());
        mvc.perform(get("/links/anything")).andExpect(status().isNotFound());
    }
}
