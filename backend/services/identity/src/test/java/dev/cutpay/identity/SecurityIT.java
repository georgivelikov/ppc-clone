package dev.cutpay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cutpay.identity.security.Totp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
class SecurityIT {

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

    private static final String PASSWORD = "correct-horse-battery";

    private MvcResult signup(String email) throws Exception {
        return mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","full_name":"Ivan Petrov","business_name":"Rose Cafe","country":"BG"}"""
                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
    }

    private String token(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("access_token").asString();
    }

    /** What the outbox holds for the notifications service: the link a person would be emailed. */
    private String emailedToken(String email, String template, String param) {
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE topic = 'notification.events' AND event_key = :to ORDER BY id DESC")
                .param("to", email).query(String.class).list().stream()
                .filter(p -> p.contains("\"" + template + "\"")).findFirst().orElseThrow();
        JsonNode data = json.readTree(payload).get("payload").get("data");
        String url = data.get(param).asString();
        return url.substring(url.indexOf("token=") + 6);
    }

    @Test
    void signupAsksForAConfirmationEmailAndTheLinkConfirmsIt() throws Exception {
        String email = "verify@example.com";
        String token = token(signup(email));
        mvc.perform(get("/v1/security").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.email_verified").value(false));

        String link = emailedToken(email, "email_verification", "verify_url");
        mvc.perform(post("/auth/email/verify").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + link + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/security").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.email_verified").value(true));
        // A link works once.
        mvc.perform(post("/auth/email/verify").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + link + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_token"));
    }

    @Test
    void aForgottenPasswordIsResetByAnEmailedLinkAndEverySessionEnds() throws Exception {
        String email = "forgot@example.com";
        MvcResult signup = signup(email);

        // Unknown addresses get the same answer, so the form reveals nothing.
        mvc.perform(post("/auth/password/forgot").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/auth/password/forgot").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        String link = emailedToken(email, "password_reset", "reset_url");

        mvc.perform(post("/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + link + "\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + link + "\",\"password\":\"a-brand-new-password\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/auth/refresh").cookie(signup.getResponse().getCookie("cutpay_rt")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"a-brand-new-password\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + link + "\",\"password\":\"yet-another-password\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void twoFactorIsSetUpConfirmedAskedForAtSignInAndRecoveryCodesWorkOnce() throws Exception {
        String email = "totp@example.com";
        String token = token(signup(email));

        JsonNode setup = json.readTree(mvc.perform(post("/v1/security/totp/setup").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauth_uri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/Cutpay:")))
                .andReturn().getResponse().getContentAsString());
        String secret = setup.get("secret").asString();

        // Not on until a code from the app is confirmed.
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/security/totp/enable").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest());
        JsonNode enabled = json.readTree(mvc.perform(post("/v1/security/totp/enable").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + Totp.code(secret, Instant.now()) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recovery_codes.length()").value(8))
                .andReturn().getResponse().getContentAsString());
        List<String> recovery = enabled.get("recovery_codes").valueStream().map(JsonNode::asString).toList();

        // Now sign-in asks for the code.
        String login = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"%s}";
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(login.formatted("")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("totp_required"));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(login.formatted(",\"totp_code\":\"123456\"")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_totp"));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(login.formatted(",\"totp_code\":\"" + Totp.code(secret, Instant.now()) + "\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.totp_enabled").value(true));

        // A recovery code gets you in once.
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(login.formatted(",\"totp_code\":\"" + recovery.get(0) + "\"")))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(login.formatted(",\"totp_code\":\"" + recovery.get(0) + "\"")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/security").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.recovery_codes_left").value(7));

        // Turning it off needs the password and a code.
        mvc.perform(post("/v1/security/totp/disable").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-password\",\"code\":\"" + Totp.code(secret, Instant.now()) + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/security/totp/disable").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\",\"code\":\"" + Totp.code(secret, Instant.now()) + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(login.formatted("")))
                .andExpect(status().isOk());
    }

    @Test
    void sessionsAreListedMarkedAndEnded() throws Exception {
        String email = "sessions@example.com";
        MvcResult first = signup(email);
        String firstToken = token(first);
        MvcResult second = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "Firefox on Linux")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();

        JsonNode list = json.readTree(mvc.perform(get("/v1/security/sessions").header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString());
        String other = null;
        int current = 0;
        for (JsonNode s : list.get("data")) {
            if (s.get("current").asBoolean()) {
                current++;
            } else {
                other = s.get("id").asString();
                assertThat(s.get("user_agent").asString()).isEqualTo("Firefox on Linux");
            }
        }
        assertThat(current).isEqualTo(1);

        // Rotating the cookie keeps the session the same one.
        mvc.perform(post("/auth/refresh").cookie(first.getResponse().getCookie("cutpay_rt"))).andExpect(status().isOk());
        mvc.perform(get("/v1/security/sessions").header("Authorization", "Bearer " + firstToken))
                .andExpect(jsonPath("$.data.length()").value(2));

        mvc.perform(delete("/v1/security/sessions/" + other).header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isNoContent());
        mvc.perform(post("/auth/refresh").cookie(second.getResponse().getCookie("cutpay_rt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingThePasswordNeedsTheOldOneAndEndsTheOtherSessions() throws Exception {
        String email = "change@example.com";
        MvcResult first = signup(email);
        String token = token(first);
        MvcResult other = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();

        mvc.perform(post("/v1/security/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"nope-nope-nope\",\"new_password\":\"a-brand-new-password\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/security/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"" + PASSWORD + "\",\"new_password\":\"a-brand-new-password\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/auth/refresh").cookie(other.getResponse().getCookie("cutpay_rt"))).andExpect(status().isUnauthorized());
        // The session that made the change stays signed in.
        mvc.perform(post("/auth/refresh").cookie(first.getResponse().getCookie("cutpay_rt"))).andExpect(status().isOk());
    }
}
