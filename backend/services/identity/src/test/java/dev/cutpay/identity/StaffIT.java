package dev.cutpay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"cutpay.staff.seed=true", "cutpay.staff.seed-password=staff-password-123", "cutpay.outbox.poll-interval=PT0.2S"})
@AutoConfigureMockMvc
@Testcontainers
class StaffIT {

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

    private String staff(String email) throws Exception {
        return json.readTree(mvc.perform(post("/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"staff-password-123\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("access_token").asString();
    }

    private record Business(String token, String id) {
    }

    private Business business() throws Exception {
        JsonNode node = json.readTree(mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"b%s@example.com","password":"correct-horse-battery","full_name":"Ivan Petrov",
                                 "business_name":"Rose Cafe","country":"BG"}""".formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andReturn().getResponse().getContentAsString());
        return new Business(node.get("access_token").asString(), node.get("merchant").get("id").asString());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void staffSignInHasItsOwnRealmAndABusinessTokenCannotUseIt() throws Exception {
        mvc.perform(post("/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@cutpay.test\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("invalid_credentials"));
        // A merchant's credentials are not staff credentials.
        Business b = business();
        mvc.perform(get("/v1/staff/merchants").header("Authorization", bearer(b.token()))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("staff_only"));
        mvc.perform(get("/v1/staff/me").header("Authorization", bearer(staff("reviewer@cutpay.test"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("staff_reviewer"));
        // And a staff token is no business: it cannot read a business's own data.
        mvc.perform(get("/v1/team/members").header("Authorization", bearer(staff("admin@cutpay.test")))).andExpect(status().isForbidden());
    }

    @Test
    void searchFindsABusinessByNameIdOrOwnerEmailAndShowsItsDetail() throws Exception {
        Business b = business();
        String admin = staff("admin@cutpay.test");
        mvc.perform(get("/v1/staff/merchants").param("q", b.id()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].status").value("pending_kyc"));
        mvc.perform(get("/v1/staff/merchants").param("q", "rose caf").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        String owner = json.readTree(mvc.perform(get("/v1/staff/merchants/" + b.id()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("merchant").get("owner_email").asString();
        mvc.perform(get("/v1/staff/merchants").param("q", owner.toUpperCase()).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data[0].id").value(b.id()));
        mvc.perform(get("/v1/staff/merchants/mer_nope").header("Authorization", bearer(admin))).andExpect(status().isNotFound());
    }

    @Test
    void reviewersDecideApplicationsAndTheBusinessSeesStaffInItsActivity() throws Exception {
        Business b = business();
        jdbc.sql("INSERT INTO onboarding_applications (id, merchant_id, submitted_at, manual_review, risk_score) VALUES (:id, :m, now(), true, 90) ON CONFLICT (merchant_id) DO UPDATE SET submitted_at = now(), manual_review = true, risk_score = 90")
                .param("id", "app_" + UUID.randomUUID().toString().replace("-", "")).param("m", b.id()).update();
        jdbc.sql("UPDATE merchants SET status = 'IN_REVIEW' WHERE id = :m").param("m", b.id()).update();
        String reviewer = staff("reviewer@cutpay.test");

        mvc.perform(get("/v1/staff/applications").header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].merchant_id").exists());
        mvc.perform(post("/v1/staff/applications/" + b.id() + "/decision").header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"reject\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("reason_required"));
        mvc.perform(post("/v1/staff/applications/" + b.id() + "/decision").header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approve\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("active")).andExpect(jsonPath("$.decided_by").value("reviewer@cutpay.test"));
        // The decision is final until the business is back in review.
        mvc.perform(post("/v1/staff/applications/" + b.id() + "/decision").header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approve\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/v1/audit_events").header("Authorization", bearer(b.token())))
                .andExpect(jsonPath("$.data[?(@.action=='staff.application_approve')].actor_email").value("reviewer@cutpay.test"));
        mvc.perform(get("/v1/staff/audit").header("Authorization", bearer(reviewer)))
                .andExpect(jsonPath("$.data[0].action").exists());
    }

    @Test
    void flagsAreForAnyStaffAndHoldingPayoutsIsForAdminsAndIsAnnounced() throws Exception {
        Business b = business();
        String reviewer = staff("reviewer@cutpay.test");
        String admin = staff("admin@cutpay.test");

        mvc.perform(put("/v1/staff/merchants/" + b.id() + "/flags/watchlist").header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Odd refund pattern\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].flag").value("watchlist"));
        mvc.perform(put("/v1/staff/merchants/" + b.id() + "/flags/payouts_frozen").header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/v1/staff/merchants/" + b.id() + "/payouts/freeze").header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Under investigation\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("staff_admin_only"));
        mvc.perform(post("/v1/staff/merchants/" + b.id() + "/payouts/freeze").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/staff/merchants/" + b.id() + "/payouts/freeze").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Under investigation\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.payouts_frozen").value(true));
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = 'PayoutsFrozen' AND event_key = :m").param("m", b.id()).query(Integer.class).single()).isEqualTo(1);

        mvc.perform(post("/v1/staff/merchants/" + b.id() + "/payouts/unfreeze").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.payouts_frozen").value(false));
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = 'PayoutsUnfrozen' AND event_key = :m").param("m", b.id()).query(Integer.class).single()).isEqualTo(1);
        mvc.perform(delete("/v1/staff/merchants/" + b.id() + "/flags/watchlist").header("Authorization", bearer(reviewer)))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
