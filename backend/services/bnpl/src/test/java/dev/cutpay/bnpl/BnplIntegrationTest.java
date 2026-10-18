package dev.cutpay.bnpl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the integration tests of this service share: one database, one broker, and a WireMock server that is both the
 * payments service and the four providers. The containers start once for the whole run (they are never stopped by a
 * test class), so every test class can use the same Spring context.
 */
@SpringBootTest(properties = {"cutpay.bnpl.provider-timeout=PT5S", "cutpay.bnpl.offers-deadline=PT10S",
        "cutpay.bnpl.job-poll-interval=PT1H", "cutpay.outbox.poll-interval=PT0.2S"})
@AutoConfigureMockMvc
abstract class BnplIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static final WireMockServer WORLD = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        POSTGRES.start();
        KAFKA.start();
        WORLD.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("cutpay.bnpl.payments-url", WORLD::baseUrl);
        registry.add("cutpay.bnpl.providers.splitnow-url", () -> WORLD.baseUrl() + "/splitnow/v1");
        registry.add("cutpay.bnpl.providers.lendo-url", () -> WORLD.baseUrl() + "/lendo/api");
        registry.add("cutpay.bnpl.providers.kreditko-url", () -> WORLD.baseUrl() + "/kreditko");
        registry.add("cutpay.bnpl.providers.paylatereu-url", () -> WORLD.baseUrl() + "/paylatereu/api");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    dev.cutpay.bnpl.adapter.ProviderHealth health;

    String merchantId;
    String secret;

    @BeforeEach
    void freshMerchantAndAnswers() {
        merchantId = "mer_" + UUID.randomUUID().toString().replace("-", "");
        secret = "pay_" + UUID.randomUUID().toString().replace("-", "") + "_secret_x";
        // Provider statistics and circuit breakers remember: every test starts from a clean slate.
        jdbc.sql("DELETE FROM bnpl_refunds").update();
        jdbc.sql("DELETE FROM bnpl_applications").update();
        health.resetAll();
        WORLD.resetAll();
        payment(secret, 30_000, "requires_payment_method", "automatic");
        providersAnswer();
    }

    RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId).claim("role", "owner"));
    }

    /** The payments service's answer about the checkout of {@code clientSecret}. */
    void payment(String clientSecret, long amount, String status, String captureMethod) {
        WORLD.stubFor(get(urlEqualTo("/internal/checkout/" + clientSecret)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"payment_id":"%s","merchant_id":"%s","livemode":false,"merchant_name":"Rose Cafe",
                         "status":"%s","amount":%d,"currency":"EUR","description":"Order 7",
                         "customer_email":"anna@example.com","capture_method":"%s","split":false}"""
                        .formatted(paymentIdOf(clientSecret), merchantId, status, amount, captureMethod))));
    }

    void payment(long amount, String status, String captureMethod) {
        payment(secret, amount, status, captureMethod);
    }

    static String paymentIdOf(String clientSecret) {
        return "pay_" + clientSecret.substring(4, 12);
    }

    String paymentId() {
        return paymentIdOf(secret);
    }

    /** What the four providers answer for a 300.00 price. */
    void providersAnswer() {
        json(post("/splitnow/v1/quotes"), """
                {"currency":"EUR","plans":[
                  {"instalments":3,"instalment_cents":[10000,10000,10000],"interest_pct":0.0,"merchant_fee_pct":3.5,"merchant_fee_cents":1050},
                  {"instalments":4,"instalment_cents":[7500,7500,7500,7500],"interest_pct":0.0,"merchant_fee_pct":3.5,"merchant_fee_cents":1050}]}""");
        json(get(urlPathEqualTo("/lendo/api/offers")), """
                {"offers":[
                  {"months":6,"monthly":"51.09","apr":9.9,"total_payable":"306.54","merchant_fee":"8.70"},
                  {"months":12,"monthly":"26.38","apr":9.9,"total_payable":"316.56","merchant_fee":"8.70"}]}""");
        json(post("/kreditko/simulations"), """
                {"termMonths":12,"monthlyMinor":2680,"aprPercent":12.9,"totalPayableMinor":32160,"feeMinor":1350}""");
        json(post("/paylatereu/api"), """
                {"ok":true,"code":"OK","data":{"payInDays":30,"customerCost":0.00,"merchantFee":7.50}}""");
    }

    static void json(com.github.tomakehurst.wiremock.client.MappingBuilder mapping, String body) {
        WORLD.stubFor(mapping.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    static void json(com.github.tomakehurst.wiremock.client.MappingBuilder mapping, int status, String body) {
        WORLD.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** The events written to the outbox about an application (or any aggregate), oldest first. */
    List<String> events(String aggregateId) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE event_key = :id ORDER BY id").param("id", aggregateId)
                .query(String.class).list();
    }
}
