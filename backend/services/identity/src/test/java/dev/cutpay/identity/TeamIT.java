package dev.cutpay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
class TeamIT {

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

    private static final String SIGNUP = """
            {"email":"%s","password":"correct-horse-battery","full_name":"Ivan Petrov",
             "business_name":"Rose Cafe","country":"BG"}""";

    private JsonNode signup(String email) throws Exception {
        return json.readTree(mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(SIGNUP.formatted(email)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String invite(String ownerToken, String email, String role) throws Exception {
        JsonNode created = json.readTree(mvc.perform(post("/v1/team/invites").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"role\":\"%s\"}".formatted(email, role)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn().getResponse().getContentAsString());
        String path = created.get("accept_path").asString();
        return path.substring(path.indexOf("token=") + 6);
    }

    private JsonNode accept(String token, String name) throws Exception {
        return json.readTree(mvc.perform(post("/auth/invites/" + token + "/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"full_name\":\"%s\",\"password\":\"another-long-password\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void anOwnerInvitesSomeoneWhoJoinsWithTheRoleAndSeesItInTheTeamAndTheAuditTrail() throws Exception {
        JsonNode owner = signup("owner1@example.com");
        String ownerToken = owner.get("access_token").asString();
        String token = invite(ownerToken, "maria@example.com", "support");

        // The link shows who is asking and for what, before anything is chosen.
        mvc.perform(get("/auth/invites/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant_name").value("Rose Cafe"))
                .andExpect(jsonPath("$.role").value("support"))
                .andExpect(jsonPath("$.account_exists").value(false));

        JsonNode joined = accept(token, "Maria Ivanova");
        assertThat(joined.get("role").asString()).isEqualTo("support");
        assertThat(joined.get("merchant").get("id").asString()).isEqualTo(owner.get("merchant").get("id").asString());

        // Used once: the same link no longer works.
        mvc.perform(post("/auth/invites/" + token + "/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"full_name\":\"Someone Else\",\"password\":\"another-long-password\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("invite_not_found"));

        mvc.perform(get("/v1/team/members").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].email").value("maria@example.com"))
                .andExpect(jsonPath("$.data[1].role").value("support"));

        mvc.perform(get("/v1/audit_events").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("team.invite_accepted"))
                .andExpect(jsonPath("$.data[1].action").value("team.invite_sent"))
                .andExpect(jsonPath("$.data[1].actor_email").value("owner1@example.com"));
    }

    @Test
    void anExistingAccountJoinsWithItsOwnPassword() throws Exception {
        JsonNode other = signup("other-owner@example.com");
        JsonNode owner = signup("owner2@example.com");
        String token = invite(owner.get("access_token").asString(), "other-owner@example.com", "viewer");

        mvc.perform(get("/auth/invites/" + token)).andExpect(jsonPath("$.account_exists").value(true));
        mvc.perform(post("/auth/invites/" + token + "/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/invites/" + token + "/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("viewer"))
                .andExpect(jsonPath("$.user.id").value(other.get("user").get("id").asString()));
    }

    @Test
    void adminsCannotCreateOrTouchAdminsAndNobodyTouchesTheOwner() throws Exception {
        JsonNode owner = signup("owner3@example.com");
        String ownerToken = owner.get("access_token").asString();
        JsonNode admin = accept(invite(ownerToken, "admin@example.com", "admin"), "Ada Admin");
        String adminToken = admin.get("access_token").asString();
        JsonNode viewer = accept(invite(ownerToken, "viewer@example.com", "viewer"), "Vera Viewer");
        String viewerToken = viewer.get("access_token").asString();
        String viewerId = viewer.get("user").get("id").asString();
        String ownerId = owner.get("user").get("id").asString();

        // An admin may bring in a developer but not another admin.
        mvc.perform(post("/v1/team/invites").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"dev@example.com\",\"role\":\"developer\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/team/invites").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"adm2@example.com\",\"role\":\"admin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("insufficient_role"));
        // The owner role cannot be handed out at all.
        mvc.perform(post("/v1/team/invites").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"own2@example.com\",\"role\":\"owner\"}"))
                .andExpect(status().isBadRequest());

        // The owner can not be changed or removed, by an admin or anyone else.
        mvc.perform(patch("/v1/team/members/" + ownerId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"viewer\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/v1/team/members/" + ownerId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        // Those below the admin can be managed by one.
        mvc.perform(patch("/v1/team/members/" + viewerId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"developer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("developer"));
        // Nobody changes their own role.
        mvc.perform(patch("/v1/team/members/" + viewerId).header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                .andExpect(status().isForbidden());
        // A person who is no longer a developer by an admin's hand, and is not an admin, cannot manage others either.
        mvc.perform(patch("/v1/team/members/" + ownerId).header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"viewer\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRemovedMemberLosesTheirSessionsAndTheirPlace() throws Exception {
        JsonNode owner = signup("owner4@example.com");
        String ownerToken = owner.get("access_token").asString();
        var joined = mvc.perform(post("/auth/invites/" + invite(ownerToken, "gone@example.com", "developer") + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"full_name\":\"Gone Soon\",\"password\":\"another-long-password\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(joined.getResponse().getContentAsString());
        String userId = body.get("user").get("id").asString();

        mvc.perform(delete("/v1/team/members/" + userId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mvc.perform(post("/auth/refresh").cookie(joined.getResponse().getCookie("cutpay_rt")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"gone@example.com\",\"password\":\"another-long-password\"}"))
                .andExpect(status().isForbidden()); // no business left
        mvc.perform(get("/v1/audit_events").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data[0].action").value("team.member_removed"));
    }

    @Test
    void invitationsCanBeReplacedAndRevoked() throws Exception {
        JsonNode owner = signup("owner5@example.com");
        String ownerToken = owner.get("access_token").asString();
        String first = invite(ownerToken, "late@example.com", "viewer");
        String second = invite(ownerToken, "LATE@example.com", "developer"); // replaces the first

        mvc.perform(get("/auth/invites/" + first)).andExpect(status().isNotFound());
        mvc.perform(get("/auth/invites/" + second)).andExpect(jsonPath("$.role").value("developer"));

        JsonNode list = json.readTree(mvc.perform(get("/v1/team/invites").header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getContentAsString());
        assertThat(list.get("data").size()).isEqualTo(2);
        String openId = null;
        for (JsonNode invite : list.get("data")) {
            if (invite.get("status").asString().equals("pending")) {
                openId = invite.get("id").asString();
            }
        }
        mvc.perform(delete("/v1/team/invites/" + openId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/auth/invites/" + second)).andExpect(status().isNotFound());
    }

    @Test
    void keyChangesAreAudited() throws Exception {
        String token = signup("owner6@example.com").get("access_token").asString();
        mvc.perform(post("/v1/api_keys").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Server\",\"type\":\"secret\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/v1/audit_events").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].action").value("api_key.created"))
                .andExpect(jsonPath("$.data[0].detail").value("Server (secret)"));
    }
}
