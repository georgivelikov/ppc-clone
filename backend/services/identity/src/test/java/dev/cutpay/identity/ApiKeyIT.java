package dev.cutpay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiKeyIT {

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
    JwtDecoder jwtDecoder;

    private String signup() throws Exception {
        String body = """
                {"email":"%s@example.com","password":"correct-horse-battery","full_name":"Ivan Petrov",
                 "business_name":"Rose Cafe","country":"BG"}""".formatted(UUID.randomUUID());
        return json.readTree(mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("access_token").asString();
    }

    private JsonNode createKey(String token, String body) throws Exception {
        return json.readTree(mvc.perform(post("/v1/api_keys").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions authenticate(String key) throws Exception {
        return mvc.perform(post("/internal/api_keys/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + key + "\"}"));
    }

    @Test
    void secretKeyIsShownOnceAndExchangesForAModeAwareToken() throws Exception {
        String token = signup();
        JsonNode created = createKey(token, "{\"name\":\"Server\",\"type\":\"secret\"}");
        String secret = created.get("secret").asString();
        assertThat(secret).matches("sk_test_[0-9A-Za-z]{32}");
        assertThat(created.get("livemode").asBoolean()).isFalse();
        assertThat(created.get("prefix").asString()).isEqualTo(secret.substring(0, 12));
        assertThat(created.get("last4").asString()).isEqualTo(secret.substring(secret.length() - 4));

        // The list never contains secrets.
        mvc.perform(get("/v1/api_keys").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].secret").doesNotExist())
                .andExpect(jsonPath("$.data[0].status").value("active"));

        String body = authenticate(secret).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("secret"))
                .andExpect(jsonPath("$.livemode").value(false))
                .andReturn().getResponse().getContentAsString();
        var jwt = jwtDecoder.decode(json.readTree(body).get("token").asString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("api");
        assertThat(jwt.getClaimAsString("mid")).startsWith("mer_");
        assertThat(jwt.getClaimAsBoolean("livemode")).isFalse();
        assertThat(jwt.getSubject()).isEqualTo(created.get("id").asString());
    }

    @Test
    void liveKeysProduceLiveTokens() throws Exception {
        String token = signup();
        String secret = createKey(token, "{\"name\":\"Prod\",\"type\":\"secret\",\"livemode\":true}")
                .get("secret").asString();
        assertThat(secret).startsWith("sk_live_");
        String body = authenticate(secret).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(jwtDecoder.decode(json.readTree(body).get("token").asString())
                .getClaimAsBoolean("livemode")).isTrue();
    }

    @Test
    void unknownAndRevokedKeysAreRejected() throws Exception {
        String token = signup();
        authenticate("sk_test_" + "x".repeat(32)).andExpect(status().isUnauthorized());

        JsonNode created = createKey(token, "{\"name\":\"Temp\",\"type\":\"secret\"}");
        authenticate(created.get("secret").asString()).andExpect(status().isOk());
        mvc.perform(delete("/v1/api_keys/" + created.get("id").asString()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        authenticate(created.get("secret").asString()).andExpect(status().isUnauthorized());
    }

    @Test
    void restrictedKeysNeedValidScopes() throws Exception {
        String token = signup();
        mvc.perform(post("/v1/api_keys").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"R\",\"type\":\"restricted\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("scopes_required"));
        mvc.perform(post("/v1/api_keys").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"R\",\"type\":\"restricted\",\"scopes\":[\"payments:admin\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_scope"));

        JsonNode ok = createKey(token,
                "{\"name\":\"R\",\"type\":\"restricted\",\"scopes\":[\"payments:read\",\"payments:read\"]}");
        assertThat(ok.get("secret").asString()).startsWith("rk_test_");
        assertThat(ok.get("scopes")).hasSize(1);
        authenticate(ok.get("secret").asString()).andExpect(jsonPath("$.scopes[0]").value("payments:read"));
    }

    @Test
    void rollingKeepsTheOldKeyForTheGracePeriodOnly() throws Exception {
        String token = signup();
        JsonNode original = createKey(token, "{\"name\":\"Server\",\"type\":\"secret\"}");

        String graceBody = mvc.perform(post("/v1/api_keys/" + original.get("id").asString() + "/roll")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grace_hours\":2}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode rolled = json.readTree(graceBody);
        assertThat(rolled.get("secret").asString()).isNotEqualTo(original.get("secret").asString());
        authenticate(original.get("secret").asString()).andExpect(status().isOk());
        authenticate(rolled.get("secret").asString()).andExpect(status().isOk());

        // Rolling again with no grace ends the previous key immediately.
        mvc.perform(post("/v1/api_keys/" + rolled.get("id").asString() + "/roll")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        authenticate(rolled.get("secret").asString()).andExpect(status().isUnauthorized());
    }

    @Test
    void keysAreScopedToTheirMerchant() throws Exception {
        String owner = signup();
        String other = signup();
        String id = createKey(owner, "{\"name\":\"Mine\",\"type\":\"secret\"}").get("id").asString();
        mvc.perform(delete("/v1/api_keys/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/api_keys").header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void anApiKeyTokenCannotManageApiKeys() throws Exception {
        String token = signup();
        String secret = createKey(token, "{\"name\":\"Server\",\"type\":\"secret\"}").get("secret").asString();
        String apiToken = json.readTree(authenticate(secret).andReturn().getResponse().getContentAsString())
                .get("token").asString();
        mvc.perform(get("/v1/api_keys").header("Authorization", "Bearer " + apiToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/api_keys").header("Authorization", "Bearer " + apiToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"type\":\"secret\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void keysRequireSignIn() throws Exception {
        mvc.perform(get("/v1/api_keys")).andExpect(status().isUnauthorized());
    }
}
