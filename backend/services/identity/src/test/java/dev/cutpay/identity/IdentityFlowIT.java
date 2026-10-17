package dev.cutpay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IdentityFlowIT {

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

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private static final String SIGNUP = """
            {"email":"%s","password":"correct-horse-battery","full_name":"Ivan Petrov",
             "business_name":"Rose Cafe","country":"BG"}""";

    @Test
    void signupLoginAndMe() throws Exception {
        MvcResult signup = mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(SIGNUP.formatted("ivan@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.merchant.id").value(org.hamcrest.Matchers.startsWith("mer_")))
                .andExpect(jsonPath("$.merchant.default_currency").value("EUR"))
                .andExpect(jsonPath("$.merchant.status").value("pending_kyc"))
                .andExpect(jsonPath("$.role").value("owner"))
                .andReturn();
        assertThat(signup.getResponse().getHeader("Set-Cookie"))
                .contains("cutpay_rt=", "HttpOnly", "SameSite=Strict", "Path=/auth");

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"IVAN@example.com\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk());

        String token = json.readTree(signup.getResponse().getContentAsString()).get("access_token").asString();
        mvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("ivan@example.com"))
                .andExpect(jsonPath("$.memberships[0].role").value("owner"));

        mvc.perform(patch("/v1/merchant").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"Rose Cafe Sofia\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("Rose Cafe Sofia"));
    }

    @Test
    void rejectsBadCredentialsDuplicatesAndAnonymousAccess() throws Exception {
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(SIGNUP.formatted("dup@example.com")))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(SIGNUP.formatted("DUP@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("email_taken"));

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dup@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_credentials"));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"whatever-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_credentials"));

        mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(SIGNUP.formatted("short@example.com").replace("correct-horse-battery", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.param").value("password"));

        mvc.perform(get("/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokensRotateAndReuseRevokesEverything() throws Exception {
        MvcResult signup = mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(SIGNUP.formatted("rotate@example.com")))
                .andExpect(status().isCreated()).andReturn();
        Cookie first = signup.getResponse().getCookie("cutpay_rt");

        MvcResult refreshed = mvc.perform(post("/auth/refresh").cookie(first))
                .andExpect(status().isOk()).andReturn();
        Cookie second = refreshed.getResponse().getCookie("cutpay_rt");
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // Replaying the first (already used) token is treated as theft...
        mvc.perform(post("/auth/refresh").cookie(first))
                .andExpect(status().isUnauthorized());
        // ...so the legitimately rotated token is now dead too.
        mvc.perform(post("/auth/refresh").cookie(second))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesJwksWithoutPrivateMaterial() throws Exception {
        MvcResult result = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk()).andReturn();
        JsonNode key = json.readTree(result.getResponse().getContentAsString()).get("keys").get(0);
        assertThat(key.get("kty").asString()).isEqualTo("RSA");
        assertThat(key.get("alg").asString()).isEqualTo("RS256");
        assertThat(key.has("d")).isFalse();
    }

    @Test
    void publishesMerchantCreatedToKafkaViaOutbox() throws Exception {
        MvcResult signup = mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(SIGNUP.formatted("events@example.com")))
                .andExpect(status().isCreated()).andReturn();
        String merchantId = json.readTree(signup.getResponse().getContentAsString())
                .get("merchant").get("id").asString();

        Properties props = new Properties();
        props.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "identity-it-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of("merchant.events"));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
            boolean found = false;
            while (!found && System.currentTimeMillis() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (merchantId.equals(record.key())) {
                        JsonNode event = json.readTree(record.value());
                        assertThat(event.get("event_type").asString()).isEqualTo("MerchantCreated");
                        assertThat(event.get("event_id").asString()).startsWith("evt_");
                        assertThat(event.get("payload").get("country").asString()).isEqualTo("BG");
                        assertThat(new String(record.headers().lastHeader("event_type").value()))
                                .isEqualTo("MerchantCreated");
                        found = true;
                    }
                }
            }
            assertThat(found).as("MerchantCreated for %s should reach Kafka", merchantId).isTrue();
        }
    }
}
