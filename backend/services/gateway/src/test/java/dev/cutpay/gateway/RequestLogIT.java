package dev.cutpay.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The whole path: an authenticated call through the gateway ends up as an event on api.requests. */
@SpringBootTest
@AutoConfigureWebTestClient
@Testcontainers
class RequestLogIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static final HttpServer UPSTREAM = startUpstream();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        String upstream = "http://localhost:" + UPSTREAM.getAddress().getPort();
        for (String name : List.of("identity", "payments", "acquirer", "ledger", "payouts", "webhooks")) {
            registry.add("cutpay.upstreams." + name, () -> upstream);
        }
    }

    @Autowired
    WebTestClient client;

    @Autowired
    JsonMapper json;

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Test
    void anApiKeyCallIsLoggedWithoutItsQueryStringOrBody() {
        client.mutateWith(mockJwt().jwt(j -> j.subject("key_77").claim("mid", "mer_log").claim("role", "api")
                        .claim("livemode", true)))
                .post().uri("/v1/refunds?card=4242424242424242")
                .header("Idempotency-Key", "idem-42")
                .bodyValue("{\"payment_intent\":\"pay_1\"}")
                .exchange().expectStatus().isOk();

        Properties props = new Properties();
        props.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "gateway-it-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of("api.requests"));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
            String raw = null;
            while (raw == null && System.currentTimeMillis() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if ("mer_log".equals(record.key())) {
                        raw = record.value();
                    }
                }
            }
            assertThat(raw).as("the request should be logged").isNotNull();
            assertThat(raw).doesNotContain("4242424242424242").doesNotContain("payment_intent\":\"pay_1");
            JsonNode event = json.readTree(raw);
            assertThat(event.get("event_type").asString()).isEqualTo("ApiRequestLogged");
            assertThat(event.get("merchant_id").asString()).isEqualTo("mer_log");
            assertThat(event.get("livemode").asBoolean()).isTrue();
            JsonNode payload = event.get("payload");
            assertThat(payload.get("method").asString()).isEqualTo("POST");
            assertThat(payload.get("path").asString()).isEqualTo("/v1/refunds");
            assertThat(payload.get("status").asInt()).isEqualTo(200);
            assertThat(payload.get("actor").asString()).isEqualTo("key_77");
            assertThat(payload.get("actor_type").asString()).isEqualTo("api_key");
            assertThat(payload.get("idempotency_key").asString()).isEqualTo("idem-42");
        }
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
