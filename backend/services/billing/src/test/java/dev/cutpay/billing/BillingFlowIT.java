package dev.cutpay.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.cutpay.billing.invoice.InvoiceService;
import dev.cutpay.billing.subscription.SubscriptionService;
import dev.cutpay.web.SimulatedClock;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The billing flows end to end: catalogue, trials, renewals, retries, plan changes, one-off invoices. The payments
 * service is a WireMock stub, and time is the simulated clock, moved forward by the test.
 */
@SpringBootTest(properties = {"cutpay.outbox.poll-interval=PT0.2S", "cutpay.billing.job-poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class BillingFlowIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static final WireMockServer PAYMENTS = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PAYMENTS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("cutpay.billing.payments-url", PAYMENTS::baseUrl);
    }

    @AfterAll
    static void stop() {
        PAYMENTS.stop();
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
    SubscriptionService subscriptions;

    @Autowired
    InvoiceService invoices;

    @Autowired
    KafkaTemplate<String, String> kafka;

    private String merchantId;

    @BeforeEach
    void freshMerchantAndStubs() {
        merchantId = "mer_" + UUID.randomUUID().toString().replace("-", "");
        PAYMENTS.resetAll();
        PAYMENTS.stubFor(get(urlPathMatching("/internal/merchants/.+/customers/cus_ok")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"cus_ok","livemode":false,"email":"anna@example.com","name":"Anna Petrova",
                         "default_payment_method":"pm_1"}""")));
        PAYMENTS.stubFor(get(urlPathMatching("/internal/merchants/.+/customers/cus_nocard")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"cus_nocard","livemode":false,"email":"boris@example.com","name":"Boris",
                         "default_payment_method":null}""")));
        PAYMENTS.stubFor(get(urlPathMatching("/internal/merchants/.+/customers/cus_missing"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"customer_not_found\",\"message\":\"No such customer\"}}")));
        cardWorks();
    }

    private void cardWorks() {
        charge("""
                {"payment_id":"pay_ok","client_secret":"pay_ok_secret","url":"http://localhost:5174/c/pay_ok_secret",
                 "status":"succeeded"}""");
    }

    private void cardDeclines(String code, String message) {
        charge("""
                {"payment_id":"pay_fail_%s","client_secret":"s","url":"http://localhost:5174/c/s","status":"failed",
                 "failure_code":"%s","failure_message":"%s"}""".formatted(UUID.randomUUID(), code, message));
    }

    private void charge(String body) {
        PAYMENTS.stubFor(post(urlEqualTo("/internal/payments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId).claim("role", "owner"));
    }

    private void advance(Duration amount) {
        ((SimulatedClock) clock).advance(amount);
    }

    private void bill() {
        subscriptions.runDueBilling(100);
        invoices.runDueRetries(100);
    }

    private String field(String body, String name) {
        return json.readTree(body).get(name).asString();
    }

    // --- helpers that go through the REST API ------------------------------------------------------------------

    private String createPrice(long amount, String interval, int trialDays) throws Exception {
        String product = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/products")
                        .with(merchant()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Coffee club\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return field(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/prices")
                        .with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"%s\",\"unit_amount\":%d,\"currency\":\"EUR\",\"interval\":\"%s\",\"trial_days\":%d}"
                                .formatted(field(product, "id"), amount, interval, trialDays)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
    }

    private ResultActions subscribe(String customer, String priceId, String extra) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/subscriptions")
                .with(merchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customer\":\"%s\",\"merchant_name\":\"Coffee Club\",\"items\":[{\"price\":\"%s\",\"quantity\":1}]%s}"
                        .formatted(customer, priceId, extra)));
    }

    private String subscribeOk(String priceId) throws Exception {
        return field(subscribe("cus_ok", priceId, "").andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "id");
    }

    private ResultActions subscription(String id) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/subscriptions/" + id)
                .with(merchant()));
    }

    private ResultActions invoicesOf(String subscriptionId) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/invoices")
                .param("subscription", subscriptionId).with(merchant()));
    }

    private long count(String sql, Object... params) {
        var statement = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            statement = statement.param("p" + (i + 1), params[i]);
        }
        return statement.query(Long.class).single();
    }

    private long emails(String kind) {
        return count("SELECT count(*) FROM notifications WHERE merchant_id = :p1 AND kind = :p2", merchantId, kind);
    }

    // --- the catalogue -----------------------------------------------------------------------------------------

    @Test
    void aProductAndItsPricesCanBeCreatedListedAndArchived() throws Exception {
        String priceId = createPrice(1500, "month", 0);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/prices/" + priceId).with(merchant()))
                .andExpect(jsonPath("$.unit_amount").value(1500))
                .andExpect(jsonPath("$.type").value("recurring"))
                .andExpect(jsonPath("$.interval").value("month"));

        mvc.perform(patch("/v1/prices/" + priceId).with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(jsonPath("$.active").value(false));
        // An archived price cannot be bought, and the amount of a price never changes.
        subscribe("cus_ok", priceId, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("price_archived"));
        mvc.perform(patch("/v1/prices/" + priceId).with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unit_amount\":9999}"))
                .andExpect(jsonPath("$.unit_amount").value(1500));
    }

    @Test
    void aPriceNeedsAnIntervalOnlyWhenItRecursAndAValidAmount() throws Exception {
        String product = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/products")
                        .with(merchant()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mug\"}"))
                .andReturn().getResponse().getContentAsString();
        String id = field(product, "id");
        var prices = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/prices");
        mvc.perform(prices.with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"%s\",\"unit_amount\":1200,\"currency\":\"EUR\"}".formatted(id)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("one_time"))
                .andExpect(jsonPath("$.interval").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/prices").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"%s\",\"unit_amount\":1200,\"currency\":\"EUR\",\"trial_days\":7}".formatted(id)))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/prices").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"%s\",\"unit_amount\":1200,\"currency\":\"XXX\",\"interval\":\"month\"}".formatted(id)))
                .andExpect(jsonPath("$.error.code").value("unsupported_currency"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/prices").with(merchant())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"%s\",\"unit_amount\":1200,\"currency\":\"EUR\",\"interval\":\"fortnight\"}".formatted(id)))
                .andExpect(jsonPath("$.error.code").value("invalid_interval"));
    }

    @Test
    void aOneTimePriceCannotStartASubscription() throws Exception {
        String product = field(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/products")
                .with(merchant()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mug\"}"))
                .andReturn().getResponse().getContentAsString(), "id");
        String price = field(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/prices")
                        .with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"%s\",\"unit_amount\":1200,\"currency\":\"EUR\"}".formatted(product)))
                .andReturn().getResponse().getContentAsString(), "id");
        subscribe("cus_ok", price, "").andExpect(jsonPath("$.error.code").value("price_not_recurring"));
    }

    // --- subscribing, trials and renewals ----------------------------------------------------------------------

    @Test
    void aSubscriptionWithoutATrialIsBilledAndStartsActive() throws Exception {
        String price = createPrice(1500, "month", 0);
        String id = subscribeOk(price);

        subscription(id).andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.items[0].unit_amount").value(1500))
                .andExpect(jsonPath("$.customer_name").value("Anna Petrova"));
        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("paid"))
                .andExpect(jsonPath("$.data[0].total").value(1500))
                .andExpect(jsonPath("$.data[0].amount_paid").value(1500))
                .andExpect(jsonPath("$.data[0].number").value("INV-0001"))
                .andExpect(jsonPath("$.data[0].billing_reason").value("subscription_create"));
        PAYMENTS.verify(postRequestedFor(urlEqualTo("/internal/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment.off_session", WireMock.equalTo("true")))
                .withRequestBody(WireMock.matchingJsonPath("$.payment.amount", WireMock.equalTo("1500")))
                .withRequestBody(WireMock.matchingJsonPath("$.payment.customer", WireMock.equalTo("cus_ok"))));
        assertThat(emails("invoice_paid")).isEqualTo(1);
    }

    @Test
    void aTrialIsFreeThenTheFirstInvoiceIsPaidAndTheNextMonthRenews() throws Exception {
        String price = createPrice(1500, "month", 7);
        String id = subscribeOk(price);

        subscription(id).andExpect(jsonPath("$.status").value("trialing")).andExpect(jsonPath("$.trial_end").isNotEmpty());
        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(0));
        PAYMENTS.verify(0, postRequestedFor(urlEqualTo("/internal/payments")));

        // Six days in nothing happens. After seven the first invoice is created and paid.
        advance(Duration.ofDays(6));
        bill();
        subscription(id).andExpect(jsonPath("$.status").value("trialing"));
        advance(Duration.ofDays(1).plusMinutes(1));
        bill();
        subscription(id).andExpect(jsonPath("$.status").value("active"));
        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("paid"))
                .andExpect(jsonPath("$.data[0].billing_reason").value("subscription_cycle"));

        // A month later the next period is billed.
        advance(Duration.ofDays(32));
        bill();
        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(2));
        subscription(id).andExpect(jsonPath("$.status").value("active"));
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type = 'SubscriptionRenewed' AND event_key = :p1", id))
                .isEqualTo(2L);
    }

    @Test
    void theSameDueSubscriptionIsBilledOnceEvenWhenTwoWorkersRunTogether() throws Exception {
        String price = createPrice(1500, "month", 0);
        String id = subscribeOk(price);
        advance(Duration.ofDays(32));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                go.await();
                return subscriptions.runDueBilling(100);
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void aCustomerWithoutASavedCardCannotSubscribeUnlessTheyAreInvoiced() throws Exception {
        String price = createPrice(1500, "month", 0);
        subscribe("cus_nocard", price, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("no_payment_method"));
        subscribe("cus_nocard", price, ",\"collection_method\":\"send_invoice\"").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("incomplete"));
        subscribe("cus_missing", price, "").andExpect(status().isNotFound());
    }

    @Test
    void aMixOfIntervalsInOneSubscriptionIsRefused() throws Exception {
        String monthly = createPrice(1500, "month", 0);
        String yearly = createPrice(15000, "year", 0);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/subscriptions")
                        .with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer\":\"cus_ok\",\"items\":[{\"price\":\"%s\",\"quantity\":1},{\"price\":\"%s\",\"quantity\":1}]}"
                                .formatted(monthly, yearly)))
                .andExpect(jsonPath("$.error.code").value("mixed_terms"));
    }

    // --- failed payments and retries ---------------------------------------------------------------------------

    @Test
    void aFailingRenewalGoesPastDueIsRetriedOnTheScheduleAndCancelsAfterTheLastTry() throws Exception {
        String price = createPrice(1500, "month", 0);
        String id = subscribeOk(price);
        cardDeclines("card_declined", "Your card was declined.");

        advance(Duration.ofDays(32));
        bill();
        subscription(id).andExpect(jsonPath("$.status").value("past_due"));
        assertThat(emails("payment_failed")).isEqualTo(1);
        String failing = json.readTree(invoicesOf(id).andReturn().getResponse().getContentAsString())
                .get("data").get(0).get("id").asString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/invoices/" + failing).with(merchant()))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.attempt_count").value(1))
                .andExpect(jsonPath("$.failure_code").value("card_declined"))
                .andExpect(jsonPath("$.next_payment_attempt").isNotEmpty());

        // Retries come on days 1, 3, 5 and 7 after the first failure, not before.
        for (int day : new int[] {1, 2, 2, 2}) {
            advance(Duration.ofDays(day).minusHours(1));
            bill();
            long attempts = count("SELECT attempt_count FROM invoices WHERE id = :p1", failing);
            advance(Duration.ofHours(1).plusMinutes(1));
            bill();
            assertThat(count("SELECT attempt_count FROM invoices WHERE id = :p1", failing)).isGreaterThan(attempts);
        }
        subscription(id).andExpect(jsonPath("$.status").value("canceled"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/invoices/" + failing).with(merchant()))
                .andExpect(jsonPath("$.status").value("uncollectible"))
                .andExpect(jsonPath("$.attempt_count").value(5));
        assertThat(emails("payment_failed")).isEqualTo(4);
        assertThat(emails("subscription_canceled")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type = 'SubscriptionCanceled' AND event_key = :p1", id))
                .isEqualTo(1L);
        // Nothing more is tried once it has been given up.
        PAYMENTS.resetRequests();
        advance(Duration.ofDays(30));
        bill();
        PAYMENTS.verify(0, postRequestedFor(urlEqualTo("/internal/payments")));
    }

    @Test
    void aRetryThatSucceedsBringsTheSubscriptionBackToActive() throws Exception {
        String price = createPrice(1500, "month", 0);
        String id = subscribeOk(price);
        cardDeclines("insufficient_funds", "Your card has insufficient funds.");
        advance(Duration.ofDays(32));
        bill();
        subscription(id).andExpect(jsonPath("$.status").value("past_due"));

        cardWorks();
        advance(Duration.ofDays(1).plusMinutes(1));
        bill();

        subscription(id).andExpect(jsonPath("$.status").value("active"));
        invoicesOf(id).andExpect(jsonPath("$.data[0].status").value("paid")).andExpect(jsonPath("$.data[0].attempt_count").value(2));
        // The remaining retries are cancelled, so nothing else is charged later.
        PAYMENTS.resetRequests();
        advance(Duration.ofDays(3));
        bill();
        PAYMENTS.verify(0, postRequestedFor(urlEqualTo("/internal/payments")));
    }

    @Test
    void theMerchantCanChooseUnpaidInsteadOfCancelingAndOwnRetryDays() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/v1/billing_settings")
                        .with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retry_days\":[2],\"final_action\":\"unpaid\"}"))
                .andExpect(jsonPath("$.final_action").value("unpaid"));
        String price = createPrice(1500, "month", 0);
        String id = subscribeOk(price);
        cardDeclines("card_declined", "Your card was declined.");

        advance(Duration.ofDays(32));
        bill();
        subscription(id).andExpect(jsonPath("$.status").value("past_due"));
        advance(Duration.ofDays(2).plusMinutes(1));
        bill();
        subscription(id).andExpect(jsonPath("$.status").value("unpaid")).andExpect(jsonPath("$.ended_at").doesNotExist());
    }

    @Test
    void retrySettingsMustMakeSense() throws Exception {
        for (String body : new String[] {"{\"retry_days\":[3,1]}", "{\"retry_days\":[0]}", "{\"retry_days\":[1],\"final_action\":\"explode\"}"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/v1/billing_settings")
                            .with(merchant()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void aFirstInvoiceThatFailsLeavesTheSubscriptionIncompleteAndItExpiresAfterADay() throws Exception {
        String price = createPrice(1500, "month", 0);
        cardDeclines("card_declined", "Your card was declined.");
        String id = subscribeOk(price);

        subscription(id).andExpect(jsonPath("$.status").value("incomplete"));
        advance(Duration.ofHours(25));
        bill();
        subscription(id).andExpect(jsonPath("$.status").value("canceled"));
        invoicesOf(id).andExpect(jsonPath("$.data[0].status").value("void"));
    }

    // --- changing a subscription -------------------------------------------------------------------------------

    @Test
    void upgradingMidPeriodAddsProrationLinesToTheNextInvoice() throws Exception {
        String basic = createPrice(1000, "month", 0);
        String pro = createPrice(3000, "month", 0);
        String id = subscribeOk(basic);
        advance(Duration.ofDays(15));

        String preview = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/v1/subscriptions/%s/proration_preview".formatted(id)).with(merchant())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"price\":\"%s\",\"quantity\":1}]}".formatted(pro)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        long net = json.readTree(preview).get("net").asLong();
        assertThat(net).isBetween(900L, 1100L); // about half of the 2000 difference

        mvc.perform(patch("/v1/subscriptions/" + id).with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"price\":\"%s\",\"quantity\":1}]}".formatted(pro)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].unit_amount").value(3000));
        // Nothing is charged yet. The upcoming invoice shows the new plan and the proration.
        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(1));
        String upcoming = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/subscriptions/%s/upcoming_invoice".formatted(id)).with(merchant()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(upcoming).get("lines").size()).isEqualTo(3);
        assertThat(json.readTree(upcoming).get("total").asLong()).isBetween(3000 + net - 2, 3000 + net + 2);

        advance(Duration.ofDays(17));
        bill();
        String renewal = json.readTree(invoicesOf(id).andReturn().getResponse().getContentAsString()).get("data").get(0).toString();
        assertThat(json.readTree(renewal).get("total").asLong()).isBetween(3000 + net - 2, 3000 + net + 2);
        assertThat(json.readTree(renewal).get("lines").size()).isEqualTo(3);
        assertThat(json.readTree(renewal).get("lines").get(1).get("proration").asBoolean()
                || json.readTree(renewal).get("lines").get(2).get("proration").asBoolean()).isTrue();
    }

    @Test
    void billImmediatelyChargesTheDifferenceNow() throws Exception {
        String basic = createPrice(1000, "month", 0);
        String pro = createPrice(3000, "month", 0);
        String id = subscribeOk(basic);
        advance(Duration.ofDays(15));

        mvc.perform(patch("/v1/subscriptions/" + id).with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"price\":\"%s\",\"quantity\":1}],\"bill_immediately\":true}".formatted(pro)))
                .andExpect(status().isOk());

        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].billing_reason").value("subscription_update"))
                .andExpect(jsonPath("$.data[0].status").value("paid"));
        // The renewal no longer carries the proration.
        advance(Duration.ofDays(17));
        bill();
        invoicesOf(id).andExpect(jsonPath("$.data[0].total").value(3000)).andExpect(jsonPath("$.data[0].lines.length()").value(1));
    }

    @Test
    void changingPlanWithoutProrationLeavesNoLines() throws Exception {
        String basic = createPrice(1000, "month", 0);
        String pro = createPrice(3000, "month", 0);
        String id = subscribeOk(basic);
        advance(Duration.ofDays(10));
        mvc.perform(patch("/v1/subscriptions/" + id).with(merchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"price\":\"%s\",\"quantity\":1}],\"proration_behavior\":\"none\"}".formatted(pro)))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/subscriptions/%s/upcoming_invoice".formatted(id)).with(merchant()))
                .andExpect(jsonPath("$.lines.length()").value(1)).andExpect(jsonPath("$.total").value(3000));
    }

    @Test
    void aPlanInAnotherIntervalCannotReplaceTheCurrentOne() throws Exception {
        String monthly = createPrice(1000, "month", 0);
        String yearly = createPrice(10000, "year", 0);
        String id = subscribeOk(monthly);
        mvc.perform(patch("/v1/subscriptions/" + id).with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"price\":\"%s\",\"quantity\":1}]}".formatted(yearly)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("terms_changed"));
    }

    // --- cancelling, pausing -----------------------------------------------------------------------------------

    @Test
    void cancelingAtThePeriodEndStopsTheRenewalInsteadOfBilling() throws Exception {
        String price = createPrice(1500, "month", 0);
        String id = subscribeOk(price);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/subscriptions/%s/cancel".formatted(id))
                        .with(merchant()).contentType(MediaType.APPLICATION_JSON).content("{\"at_period_end\":true}"))
                .andExpect(jsonPath("$.status").value("active")).andExpect(jsonPath("$.cancel_at_period_end").value(true));

        advance(Duration.ofDays(32));
        bill();
        subscription(id).andExpect(jsonPath("$.status").value("canceled"));
        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void cancelingNowEndsItAndCannotBeDoneTwice() throws Exception {
        String price = createPrice(1500, "month", 0);
        String id = subscribeOk(price);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/subscriptions/%s/cancel".formatted(id))
                        .with(merchant())).andExpect(jsonPath("$.status").value("canceled"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/subscriptions/%s/cancel".formatted(id))
                        .with(merchant())).andExpect(status().isConflict());
        advance(Duration.ofDays(40));
        bill();
        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void aPausedSubscriptionIsNotBilledAndResumesWithAFreshPeriod() throws Exception {
        String price = createPrice(1500, "month", 0);
        String id = subscribeOk(price);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/subscriptions/%s/pause".formatted(id))
                        .with(merchant())).andExpect(jsonPath("$.collection_paused").value(true));

        advance(Duration.ofDays(70));
        bill();
        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/subscriptions/%s/resume".formatted(id))
                        .with(merchant())).andExpect(jsonPath("$.collection_paused").value(false));
        invoicesOf(id).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].status").value("paid"));
        subscription(id).andExpect(jsonPath("$.status").value("active"));
    }

    // --- one-off invoices --------------------------------------------------------------------------------------

    private String draftInvoice() throws Exception {
        return field(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices")
                        .with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customer":"cus_ok","currency":"EUR","merchant_name":"Coffee Club","tax_rate_percent":20,
                                 "lines":[{"description":"Catering","unit_amount":10000,"quantity":2},
                                          {"description":"Discount","unit_amount":-1000,"quantity":1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.number").doesNotExist())
                .andExpect(jsonPath("$.subtotal").value(19000))
                .andExpect(jsonPath("$.tax").value(3800))
                .andExpect(jsonPath("$.total").value(22800))
                .andReturn().getResponse().getContentAsString(), "id");
    }

    @Test
    void aOneOffInvoiceIsDraftedFinalizedNumberedAndPaidFromTheSavedCard() throws Exception {
        String id = draftInvoice();
        String finalized = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/v1/invoices/%s/finalize".formatted(id)).with(merchant()))
                .andExpect(jsonPath("$.status").value("open")).andExpect(jsonPath("$.number").value("INV-0001"))
                .andExpect(jsonPath("$.hosted_invoice_url").value(org.hamcrest.Matchers.containsString("/i/")))
                .andReturn().getResponse().getContentAsString();
        assertThat(finalized).contains("\"amount_due\":22800");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/v1/invoices/%s/finalize".formatted(id)).with(merchant()))
                .andExpect(status().isConflict());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/pay".formatted(id)).with(merchant()))
                .andExpect(jsonPath("$.paid").value(true)).andExpect(jsonPath("$.invoice.status").value("paid"))
                .andExpect(jsonPath("$.invoice.amount_paid").value(22800));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/pay".formatted(id)).with(merchant()))
                .andExpect(status().isConflict());
    }

    @Test
    void aRefusedCardOnManualPaymentIsReportedWithoutRetriesOrAStatusChange() throws Exception {
        String id = draftInvoice();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/finalize".formatted(id)).with(merchant()));
        cardDeclines("card_declined", "Your card was declined.");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/pay".formatted(id)).with(merchant()))
                .andExpect(jsonPath("$.paid").value(false)).andExpect(jsonPath("$.failure_code").value("card_declined"))
                .andExpect(jsonPath("$.invoice.status").value("open"));
        assertThat(count("SELECT count(*) FROM dunning_attempts WHERE invoice_id = :p1", id)).isZero();
    }

    @Test
    void aDraftCanBeDeletedAndAnOpenInvoiceVoidedButNotAPaidOne() throws Exception {
        String draft = draftInvoice();
        mvc.perform(delete("/v1/invoices/" + draft).with(merchant())).andExpect(status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/invoices/" + draft).with(merchant()))
                .andExpect(status().isNotFound());

        String open = draftInvoice();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/finalize".formatted(open)).with(merchant()));
        mvc.perform(delete("/v1/invoices/" + open).with(merchant())).andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/void".formatted(open)).with(merchant()))
                .andExpect(jsonPath("$.status").value("void"));

        String paid = draftInvoice();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/finalize".formatted(paid)).with(merchant()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/pay".formatted(paid)).with(merchant()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/void".formatted(paid)).with(merchant()))
                .andExpect(status().isConflict());
    }

    @Test
    void invoicesAreNumberedInOrderPerMerchant() throws Exception {
        String first = draftInvoice();
        String second = draftInvoice();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/finalize".formatted(second)).with(merchant()))
                .andExpect(jsonPath("$.number").value("INV-0001"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/finalize".formatted(first)).with(merchant()))
                .andExpect(jsonPath("$.number").value("INV-0002"));
    }

    @Test
    void theHostedInvoiceHidesDraftsRendersAPdfAndIsPaidByThePaymentEvent() throws Exception {
        String id = draftInvoice();
        String token = tokenOf(id);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/invoices/" + token))
                .andExpect(status().isNotFound());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/finalize".formatted(id)).with(merchant()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/invoices/" + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.number").value("INV-0001"))
                .andExpect(jsonPath("$.payable").value(true)).andExpect(jsonPath("$.total").value(22800))
                .andExpect(jsonPath("$.merchant_name").value("Coffee Club"))
                .andExpect(jsonPath("$.lines.length()").value(2));
        byte[] pdf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/invoices/" + token + "/pdf"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");

        // The customer pays on the hosted checkout; the payment carries the invoice id.
        charge("""
                {"payment_id":"pay_hosted","client_secret":"pay_hosted_secret","url":"http://localhost:5174/c/pay_hosted_secret",
                 "status":"requires_payment_method"}""");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/invoices/" + token + "/pay"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("http://localhost:5174/c/pay_hosted_secret"));
        PAYMENTS.verify(postRequestedFor(urlEqualTo("/internal/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment.metadata.invoice_id", WireMock.equalTo(id)))
                .withRequestBody(WireMock.matchingJsonPath("$.payment.return_url", WireMock.containing("/i/" + token))));

        kafka.send("payment.events", "pay_hosted", paymentEvent("PaymentSucceeded", "pay_hosted", id, false, null));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/invoices/" + id).with(merchant()))
                        .andExpect(jsonPath("$.status").value("paid")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/invoices/" + token))
                .andExpect(jsonPath("$.payable").value(false)).andExpect(jsonPath("$.status").value("paid"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/invoices/" + token + "/pay"))
                .andExpect(status().isConflict());
    }

    @Test
    void aFailureOnTheHostedPageIsNotAFailedAttemptButAnOffSessionOneIs() throws Exception {
        String price = createPrice(1500, "month", 0);
        String id = subscribeOk(price);
        cardDeclines("card_declined", "Your card was declined.");
        advance(Duration.ofDays(32));
        // A renewal invoice exists but the stub keeps declining: the first attempt counts.
        bill();
        String invoiceId = json.readTree(invoicesOf(id).andReturn().getResponse().getContentAsString())
                .get("data").get(0).get("id").asString();
        long before = count("SELECT attempt_count FROM invoices WHERE id = :p1", invoiceId);

        kafka.send("payment.events", "pay_x", paymentEvent("PaymentFailed", "pay_x", invoiceId, false, "card_declined"));
        kafka.send("payment.events", "pay_y", paymentEvent("PaymentFailed", "pay_y", invoiceId, true, "card_declined"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM processed_events WHERE event_id IN ('evt_pay_x', 'evt_pay_y')")).isEqualTo(2L));

        // Neither schedules another retry: the on-session one is ignored and the off-session one was already scheduled.
        assertThat(count("SELECT attempt_count FROM invoices WHERE id = :p1", invoiceId)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM dunning_attempts WHERE invoice_id = :p1", invoiceId)).isLessThanOrEqualTo(2L);
    }

    @Test
    void thePaymentEventForAnInvoiceIsHandledOnceWhenRedelivered() throws Exception {
        String id = draftInvoice();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invoices/%s/finalize".formatted(id)).with(merchant()));
        String event = paymentEvent("PaymentSucceeded", "pay_twice", id, false, null);
        kafka.send("payment.events", "pay_twice", event);
        kafka.send("payment.events", "pay_twice", event);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/invoices/" + id).with(merchant()))
                        .andExpect(jsonPath("$.status").value("paid")));
        Thread.sleep(500);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type = 'InvoicePaid' AND event_key = :p1", id)).isEqualTo(1L);
    }

    // --- figures and isolation ---------------------------------------------------------------------------------

    @Test
    void monthlyRecurringRevenueCountsPayingSubscriptionsOnly() throws Exception {
        String monthly = createPrice(1500, "month", 0);
        String yearly = createPrice(12000, "year", 0);
        subscribeOk(monthly);
        subscribeOk(yearly);
        subscribeOk(createPrice(900, "month", 14)); // trialing: not revenue yet

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/subscription_stats").with(merchant()))
                .andExpect(jsonPath("$.mrr.EUR").value(2500))
                .andExpect(jsonPath("$.by_status.active").value(2))
                .andExpect(jsonPath("$.by_status.trialing").value(1));
    }

    @Test
    void anotherMerchantCannotSeeOrChangeMySubscriptionsAndInvoices() throws Exception {
        String price = createPrice(1500, "month", 0);
        String id = subscribeOk(price);
        RequestPostProcessor other = jwt().jwt(j -> j.claim("mid", "mer_other"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/subscriptions/" + id).with(other))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/subscriptions/%s/cancel".formatted(id)).with(other))
                .andExpect(status().isNotFound());
        String invoice = json.readTree(invoicesOf(id).andReturn().getResponse().getContentAsString()).get("data").get(0).get("id").asString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/invoices/" + invoice).with(other))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/prices/" + price).with(other))
                .andExpect(status().isNotFound());
    }

    // --- helpers for events ------------------------------------------------------------------------------------

    private String tokenOf(String invoiceId) {
        return jdbc.sql("SELECT hosted_token FROM invoices WHERE id = :p1").param("p1", invoiceId)
                .query(String.class).single();
    }

    private String paymentEvent(String type, String paymentId, String invoiceId, boolean offSession, String failureCode) {
        return """
                {"event_id":"evt_%s","event_type":"%s","occurred_at":"2026-10-18T10:00:00Z","aggregate_id":"%s",
                 "merchant_id":"%s","livemode":false,"version":1,
                 "payload":{"payment_id":"%s","status":"%s","amount":22800,"currency":"EUR","off_session":%s,
                            "failure_code":%s,"metadata":{"invoice_id":"%s"}}}"""
                .formatted(paymentId, type, paymentId, merchantId, paymentId,
                        "PaymentSucceeded".equals(type) ? "succeeded" : "failed", offSession,
                        failureCode == null ? "null" : "\"" + failureCode + "\"", invoiceId);
    }
}
