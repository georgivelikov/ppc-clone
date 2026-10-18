package dev.cutpay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "cutpay.outbox.poll-interval=PT0.2S")
@AutoConfigureMockMvc
@Testcontainers
class ConnectedAccountIT {

    private static final String IBAN = "BG80BNBG96611020345678";

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

    private String signup() throws Exception {
        String body = """
                {"email":"%s@example.com","password":"correct-horse-battery","full_name":"Ivan Petrov",
                 "business_name":"Delivery Hero","country":"BG"}""".formatted(UUID.randomUUID());
        return json.readTree(mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("access_token").asString();
    }

    private String createAccount(String token, String iban) throws Exception {
        return mvc.perform(post("/v1/connected_accounts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pizza Palace\",\"email\":\"owner@pizza.example\",\"iban\":\"%s\"}".formatted(iban)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aPlatformCreatesAConnectedAccountThatShowsOnlyAMaskedIban() throws Exception {
        String token = signup();
        String body = mvc.perform(post("/v1/connected_accounts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pizza Palace\",\"iban\":\"bg80 bnbg 9661 1020 3456 78\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.object").value("connected_account"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.country").value("BG"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.iban_last4").value("5678"))
                .andExpect(jsonPath("$.iban").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asString();
        assertThat(id).startsWith("acct_");

        mvc.perform(get("/v1/connected_accounts/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Pizza Palace"));
        mvc.perform(get("/v1/connected_accounts").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void creatingOneAnnouncesTheIbanToThePayoutsAndLedgerServicesOnlyOnTheInternalTopic() throws Exception {
        String token = signup();
        String id = json.readTree(createAccount(token, IBAN)).get("id").asString();
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE event_type = 'ConnectedAccountCreated' AND event_key = :id")
                .param("id", id).query(String.class).single();
        assertThat(payload).contains("\"iban\":\"" + IBAN + "\"").contains("\"account_id\":\"" + id + "\"");
        assertThat(jdbc.sql("SELECT topic FROM outbox WHERE event_key = :id").param("id", id).query(String.class).single())
                .isEqualTo("merchant.events");
    }

    @Test
    void anInvalidIbanIsRefused() throws Exception {
        String token = signup();
        mvc.perform(post("/v1/connected_accounts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"iban\":\"BG80BNBG96611020345679\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_iban"));
    }

    @Test
    void anotherPlatformCannotSeeOrChangeIt() throws Exception {
        String owner = signup();
        String other = signup();
        String id = json.readTree(createAccount(owner, IBAN)).get("id").asString();
        mvc.perform(get("/v1/connected_accounts/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/v1/connected_accounts/" + id).header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/connected_accounts").header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/internal/platforms/%s/connected_accounts/%s".formatted(
                        json.readTree(mvc.perform(get("/me").header("Authorization", "Bearer " + other)).andReturn().getResponse()
                                .getContentAsString()).get("memberships").get(0).get("merchant_id").asString(), id)))
                .andExpect(status().isNotFound());
    }

    @Test
    void changingTheIbanAnnouncesItAndKeepsTheCountry() throws Exception {
        String token = signup();
        String id = json.readTree(createAccount(token, IBAN)).get("id").asString();
        mvc.perform(patch("/v1/connected_accounts/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pizza Palace Ltd\",\"iban\":\"BG18RZBB91550123456789\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Pizza Palace Ltd"))
                .andExpect(jsonPath("$.iban_last4").value("6789"));
        mvc.perform(patch("/v1/connected_accounts/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"iban\":\"DE89370400440532013000\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("iban_country_changed"));
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = 'ConnectedAccountUpdated' AND event_key = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void aDisabledAccountCannotReceiveMoneyOrBeEdited() throws Exception {
        String token = signup();
        String id = json.readTree(createAccount(token, IBAN)).get("id").asString();
        mvc.perform(post("/v1/connected_accounts/%s/disable".formatted(id)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("disabled"));
        mvc.perform(patch("/v1/connected_accounts/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isConflict());
        // The platform check payments makes before accepting a split sees it as disabled.
        String platform = jdbc.sql("SELECT platform_merchant_id FROM connected_accounts WHERE id = :id").param("id", id)
                .query(String.class).single();
        mvc.perform(get("/internal/platforms/%s/connected_accounts/%s".formatted(platform, id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("disabled"));
    }

    @Test
    void aViewerCannotChangeConnectedAccountsButCanReadThem() throws Exception {
        String token = signup();
        String id = json.readTree(createAccount(token, IBAN)).get("id").asString();
        mvc.perform(get("/internal/platforms/x/connected_accounts/" + id)).andExpect(status().isNotFound());
    }
}
