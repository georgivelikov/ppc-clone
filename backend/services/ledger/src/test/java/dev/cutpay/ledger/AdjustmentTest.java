package dev.cutpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** A correction by staff needs a second admin; only then do the books change. */
@SpringBootTest(properties = {"cutpay.outbox.poll-interval=PT0.2S", "cutpay.ledger.availability-poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class AdjustmentTest {

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

    private static RequestPostProcessor staff(String email, String role) {
        return jwt().jwt(j -> j.claim("email", email).claim("role", role).claim("realm", "staff"));
    }

    private String request(RequestPostProcessor who, String merchant, long amount) throws Exception {
        return json.readTree(mvc.perform(post("/v1/staff/adjustments").with(who).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchant_id\":\"%s\",\"currency\":\"EUR\",\"amount\":%d,\"reason\":\"Goodwill after an outage\"}".formatted(merchant, amount)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("pending"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private long balance(String merchant) {
        return jdbc.sql("SELECT COALESCE(SUM(p.amount), 0) FROM postings p JOIN accounts a ON a.id = p.account_id WHERE a.merchant_id = :m AND a.type = 'merchant_available'")
                .param("m", merchant).query(Long.class).single();
    }

    @Test
    void anAdjustmentChangesNothingUntilADifferentAdminApprovesIt() throws Exception {
        String merchant = "mer_" + UUID.randomUUID().toString().replace("-", "");
        var alex = staff("alex@cutpay.test", "staff_admin");
        var sam = staff("sam@cutpay.test", "staff_admin");
        String id = request(alex, merchant, 2_500);
        assertThat(balance(merchant)).isZero();

        // Not by the one who asked, not by a reviewer, not by a business.
        mvc.perform(post("/v1/staff/adjustments/" + id + "/approve").with(alex)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("four_eyes"));
        mvc.perform(post("/v1/staff/adjustments/" + id + "/approve").with(staff("rita@cutpay.test", "staff_reviewer"))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/staff/adjustments/" + id + "/approve").with(jwt().jwt(j -> j.claim("mid", merchant).claim("role", "owner"))))
                .andExpect(status().isForbidden());
        assertThat(balance(merchant)).isZero();

        mvc.perform(post("/v1/staff/adjustments/" + id + "/approve").with(sam).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"ok\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved")).andExpect(jsonPath("$.decided_by").value("sam@cutpay.test"));
        assertThat(balance(merchant)).isEqualTo(-2_500); // owed to the merchant: credit
        assertThat(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT net FROM balance_transactions WHERE merchant_id = :m AND type = 'adjustment'").param("m", merchant).query(Long.class).single()).isEqualTo(2_500);
        mvc.perform(post("/v1/staff/adjustments/" + id + "/approve").with(sam)).andExpect(status().isConflict());
    }

    @Test
    void aNegativeAdjustmentTakesMoneyBackAndARejectedOneNeverTouchesTheBooks() throws Exception {
        String merchant = "mer_" + UUID.randomUUID().toString().replace("-", "");
        var alex = staff("alex@cutpay.test", "staff_admin");
        var sam = staff("sam@cutpay.test", "staff_admin");
        mvc.perform(post("/v1/staff/adjustments/" + request(alex, merchant, -900) + "/approve").with(sam)).andExpect(status().isOk());
        assertThat(balance(merchant)).isEqualTo(900);

        String rejected = request(sam, merchant, 7_000);
        mvc.perform(post("/v1/staff/adjustments/" + rejected + "/reject").with(sam)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/staff/adjustments/" + rejected + "/reject").with(alex)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("rejected"));
        assertThat(balance(merchant)).isEqualTo(900);
        mvc.perform(get("/v1/staff/adjustments").param("status", "rejected").with(alex)).andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void requestsAreChecked() throws Exception {
        var alex = staff("alex@cutpay.test", "staff_admin");
        for (String body : new String[] {
                "{\"merchant_id\":\"mer_1\",\"currency\":\"EUR\",\"amount\":0,\"reason\":\"A good long reason\"}",
                "{\"merchant_id\":\"mer_1\",\"currency\":\"XXX\",\"amount\":100,\"reason\":\"A good long reason\"}",
                "{\"merchant_id\":\"mer_1\",\"currency\":\"EUR\",\"amount\":100,\"reason\":\"short\"}"}) {
            mvc.perform(post("/v1/staff/adjustments").with(alex).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/v1/staff/adjustments").with(staff("rita@cutpay.test", "staff_reviewer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchant_id\":\"mer_1\",\"currency\":\"EUR\",\"amount\":100,\"reason\":\"A good long reason\"}"))
                .andExpect(status().isForbidden());
    }
}
