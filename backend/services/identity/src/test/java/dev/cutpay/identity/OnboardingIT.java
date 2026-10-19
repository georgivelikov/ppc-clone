package dev.cutpay.identity;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.cutpay.identity.domain.Merchant;
import dev.cutpay.identity.onboarding.OnboardingService;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The whole application, against a stand-in for the verification provider. */
@SpringBootTest(properties = {"cutpay.outbox.poll-interval=PT0.2S", "cutpay.kyc.poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class OnboardingIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static final WireMockServer KYC = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        KYC.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("cutpay.kyc.url", KYC::baseUrl);
    }

    @AfterAll
    static void stop() {
        KYC.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OnboardingService onboarding;

    @Autowired
    TransactionTemplate tx;

    private static final String IBAN = "BG80BNBG96611020345678";

    @BeforeEach
    void provider() {
        KYC.resetAll();
        KYC.stubFor(WireMock.get(urlPathEqualTo("/registry/BG/123456789")).willReturn(json(
                "{\"registration_number\":\"123456789\",\"country\":\"BG\",\"name\":\"Rose Cafe OOD\",\"legal_form\":\"OOD\","
                        + "\"status\":\"active\",\"incorporated_on\":\"2015-03-01\",\"address\":{\"line1\":\"1 Vitosha\",\"city\":\"Sofia\","
                        + "\"postal_code\":\"1000\"},\"representatives\":[{\"name\":\"Ivan Petrov\",\"role\":\"manager\"}]}")));
        KYC.stubFor(WireMock.get(urlPathEqualTo("/registry/BG/123456000")).willReturn(aResponse().withStatus(404)));
        KYC.stubFor(WireMock.post(urlEqualTo("/screening")).willReturn(json("{\"full_name\":\"x\",\"hits\":[]}")));
        // A verification gets the id of the last digit of the document number, and is answered by that digit.
        for (String digit : new String[] {"0", "9", "1"}) {
            KYC.stubFor(WireMock.post(urlEqualTo("/verifications"))
                    .withRequestBody(matchingJsonPath("$.document_number", WireMock.matching(".*" + digit)))
                    .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                            .withBody("{\"id\":\"vrf_" + digit + "\",\"status\":\"pending\"}")));
        }
        KYC.stubFor(WireMock.get(urlEqualTo("/verifications/vrf_1")).willReturn(json("{\"id\":\"vrf_1\",\"status\":\"approved\"}")));
        KYC.stubFor(WireMock.get(urlEqualTo("/verifications/vrf_0")).willReturn(json(
                "{\"id\":\"vrf_0\",\"status\":\"rejected\",\"reason\":\"The document could not be verified as genuine.\"}")));
        KYC.stubFor(WireMock.get(urlEqualTo("/verifications/vrf_9")).willReturn(json(
                "{\"id\":\"vrf_9\",\"status\":\"needs_more_info\",\"reason\":\"The photo is not clear enough.\"}")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private record Business(String token, String merchantId) {
    }

    private Business signup() throws Exception {
        String body = mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"o%s@example.com","password":"correct-horse-battery","full_name":"Ivan Petrov",
                                 "business_name":"Rose Cafe","country":"BG"}""".formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return new Business(node.get("access_token").asString(), node.get("merchant").get("id").asString());
    }

    private static <T extends org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<T>> T auth(T builder, Business b) {
        builder.header("Authorization", "Bearer " + b.token());
        return builder;
    }

    private void putJson(Business b, String path, String body) throws Exception {
        mvc.perform(auth(put(path), b).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    private String addPerson(Business b, String role, String name, String documentNumber, int pct) throws Exception {
        String app = mvc.perform(auth(post("/v1/onboarding/persons"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"%s","name":"%s","date_of_birth":"1985-04-12","nationality":"BG","ownership_pct":%s,
                                 "document_type":"id_card","document_number":"%s"}""".formatted(role, name, pct == 0 ? "null" : pct, documentNumber)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode persons = json.readTree(app).get("persons");
        return persons.get(persons.size() - 1).get("id").asString();
    }

    private void upload(Business b, String type, String personId) throws Exception {
        var file = new MockMultipartFile("file", type + ".png", "image/png", new byte[] {1, 2, 3});
        var request = auth(multipart("/v1/onboarding/documents").file(file).param("type", type), b);
        if (personId != null) {
            request.param("person_id", personId);
        }
        mvc.perform(request).andExpect(status().isCreated());
    }

    /** Fills every step with a representative who is also the owner. */
    private String complete(Business b, String industry, long volume, String documentNumber) throws Exception {
        putJson(b, "/v1/onboarding/business", "{\"business_type\":\"company\",\"industry\":\"%s\",\"registration_number\":\"123456789\"}".formatted(industry));
        putJson(b, "/v1/onboarding/profile", "{\"website\":\"https://rose.example\",\"description\":\"A small cafe selling coffee and cakes\",\"expected_monthly_volume\":%d}".formatted(volume));
        putJson(b, "/v1/onboarding/bank", "{\"iban\":\"%s\",\"account_holder\":\"Rose Cafe OOD\"}".formatted(IBAN));
        String rep = addPerson(b, "representative", "Ivan Petrov", documentNumber, 0);
        addPerson(b, "ubo", "Maria Ivanova", "AB000001", 60);
        upload(b, "id_front", rep);
        upload(b, "selfie", rep);
        upload(b, "bank_proof", null);
        String app = mvc.perform(auth(get("/v1/onboarding"), b)).andReturn().getResponse().getContentAsString();
        // The second person still needs their documents.
        String ubo = json.readTree(app).get("persons").get(1).get("id").asString();
        upload(b, "id_front", ubo);
        upload(b, "selfie", ubo);
        return rep;
    }

    private void submit(Business b) throws Exception {
        mvc.perform(auth(post("/v1/onboarding/submit"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted_terms\":true,\"signed_name\":\"Ivan Petrov\"}"))
                .andExpect(status().isOk());
    }

    private String stateOf(Business b) throws Exception {
        return json.readTree(mvc.perform(auth(get("/v1/onboarding"), b)).andReturn().getResponse().getContentAsString())
                .get("status").asString();
    }

    private int events(Business b, String type) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE topic = 'onboarding.events' AND event_key = :m AND event_type = :t")
                .param("m", b.merchantId()).param("t", type).query(Integer.class).single();
    }

    // --- tests ----------------------------------------------------------------------------------------------------

    @Test
    void aCompleteApplicationIsCheckedAndApprovedWithoutAPerson() throws Exception {
        Business b = signup();
        assertThat(stateOf(b)).isEqualTo("pending_kyc");
        complete(b, "hospitality", 2_000_000, "AB123451");
        submit(b);
        assertThat(stateOf(b)).isEqualTo("in_review");
        assertThat(events(b, "ApplicationSubmitted")).isEqualTo(1);

        assertThat(onboarding.pollVerifications()).isEqualTo(1);

        assertThat(stateOf(b)).isEqualTo("active");
        mvc.perform(auth(get("/v1/onboarding"), b))
                .andExpect(jsonPath("$.decided_by").value("system"))
                .andExpect(jsonPath("$.risk_score").value(10))
                .andExpect(jsonPath("$.manual_review").value(false))
                .andExpect(jsonPath("$.persons[0].verification_status").value("approved"))
                .andExpect(jsonPath("$.missing.length()").value(0));
        mvc.perform(auth(get("/me"), b)).andExpect(status().isOk());
        assertThat(events(b, "ApplicationDecided")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT payload FROM outbox WHERE topic = 'onboarding.events' AND event_type = 'ApplicationDecided' AND event_key = :m")
                .param("m", b.merchantId()).query(String.class).single()).contains("\"status\":\"active\"");
    }

    @Test
    void aBlurryDocumentAsksForMoreAndTheApplicationCanBeFixedAndSentAgain() throws Exception {
        Business b = signup();
        String rep = complete(b, "retail", 1_000_000, "AB123459");
        submit(b);
        onboarding.pollVerifications();

        assertThat(stateOf(b)).isEqualTo("needs_more_info");
        mvc.perform(auth(get("/v1/onboarding"), b)).andExpect(jsonPath("$.decision_reason").value(org.hamcrest.Matchers.containsString("not clear enough")));
        assertThat(events(b, "ApplicationDecided")).isEqualTo(1);

        // Send a better document and try again.
        mvc.perform(auth(put("/v1/onboarding/persons/" + rep), b).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"representative","name":"Ivan Petrov","date_of_birth":"1985-04-12","nationality":"BG",
                                 "document_type":"id_card","document_number":"AB123451"}"""))
                .andExpect(status().isOk());
        upload(b, "id_front", rep);
        submit(b);
        onboarding.pollVerifications();
        assertThat(stateOf(b)).isEqualTo("active");
    }

    @Test
    void aDocumentThatIsNotGenuineRejectsTheApplication() throws Exception {
        Business b = signup();
        complete(b, "retail", 1_000_000, "AB123450");
        submit(b);
        onboarding.pollVerifications();
        assertThat(stateOf(b)).isEqualTo("rejected");
        // Rejected is final: it can neither be edited nor sent again.
        mvc.perform(auth(put("/v1/onboarding/bank"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"iban\":\"%s\",\"account_holder\":\"X Y\"}".formatted(IBAN)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("application_locked"));
    }

    @Test
    void aRiskyBusinessWaitsForAPersonWhateverTheChecksSay() throws Exception {
        Business b = signup();
        complete(b, "gambling", 20_000_000, "AB123451"); // restricted industry, expects more than 100,000 a month
        submit(b);
        mvc.perform(auth(get("/v1/onboarding"), b)).andExpect(jsonPath("$.manual_review").value(true))
                .andExpect(jsonPath("$.risk_score").value(90))
                .andExpect(jsonPath("$.risk_reasons[0]").value("Restricted industry: gambling"));

        onboarding.pollVerifications();
        assertThat(stateOf(b)).isEqualTo("in_review");
        assertThat(jdbc.sql("SELECT verification_status FROM persons WHERE merchant_id = :m AND role = 'representative'")
                .param("m", b.merchantId()).query(String.class).single()).isEqualTo("approved");

        tx.executeWithoutResult(s -> onboarding.decide(b.merchantId(), Merchant.Status.ACTIVE, "Looks legitimate", "staff_1"));
        assertThat(stateOf(b)).isEqualTo("active");
        mvc.perform(auth(get("/v1/onboarding"), b)).andExpect(jsonPath("$.decided_by").value("staff_1"));
    }

    @Test
    void aSanctionsHitSendsItToReviewWithAHighScore() throws Exception {
        KYC.stubFor(WireMock.post(urlEqualTo("/screening")).withRequestBody(equalToJson("{\"full_name\":\"Maria Ivanova\",\"country\":\"BG\"}"))
                .willReturn(json("{\"full_name\":\"Maria Ivanova\",\"hits\":[{\"list\":\"sanctions\",\"name\":\"Maria Ivanova\"}]}")));
        Business b = signup();
        complete(b, "retail", 1_000_000, "AB123451");
        submit(b);
        mvc.perform(auth(get("/v1/onboarding"), b)).andExpect(jsonPath("$.manual_review").value(true))
                .andExpect(jsonPath("$.persons[1].screening").value("sanctions"));
    }

    @Test
    void whatIsMissingIsNamedAndBadAnswersAreRefused() throws Exception {
        Business b = signup();
        mvc.perform(auth(get("/v1/onboarding"), b)).andExpect(jsonPath("$.missing.length()").value(4));
        mvc.perform(auth(post("/v1/onboarding/submit"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted_terms\":true,\"signed_name\":\"Ivan Petrov\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("application_incomplete"));

        mvc.perform(auth(put("/v1/onboarding/business"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"business_type\":\"company\",\"industry\":\"retail\",\"registration_number\":\"123456000\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("company_not_found"));
        mvc.perform(auth(put("/v1/onboarding/business"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"business_type\":\"company\",\"industry\":\"retail\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("registration_number_required"));
        mvc.perform(auth(put("/v1/onboarding/bank"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"iban\":\"BG80BNBG96611020345679\",\"account_holder\":\"Rose Cafe\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_iban"));
        mvc.perform(auth(post("/v1/onboarding/persons"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ubo","name":"Small Owner","date_of_birth":"1985-04-12","nationality":"BG","ownership_pct":10,
                                 "document_type":"id_card","document_number":"AB123456"}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_ownership"));
        mvc.perform(auth(post("/v1/onboarding/persons"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"representative","name":"Too Young","date_of_birth":"2015-04-12","nationality":"BG",
                                 "document_type":"id_card","document_number":"AB123456"}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("too_young"));
        mvc.perform(auth(multipart("/v1/onboarding/documents").file(new MockMultipartFile("file", "x.exe", "application/x-msdownload", new byte[] {1}))
                        .param("type", "bank_proof"), b))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_file_type"));
    }

    @Test
    void documentsAreReplacedServedAsDownloadsAndPrivateToTheBusiness() throws Exception {
        Business b = signup();
        String person = addPerson(b, "representative", "Ivan Petrov", "AB123451", 0);
        upload(b, "id_front", person);
        upload(b, "id_front", person); // replaces
        String app = mvc.perform(auth(get("/v1/onboarding"), b)).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(app).get("documents").size()).isEqualTo(1);
        String docId = json.readTree(app).get("documents").get(0).get("id").asString();

        mvc.perform(auth(get("/v1/onboarding/documents/" + docId + "/file"), b))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[] {1, 2, 3}))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/octet-stream"));
        Business other = signup();
        mvc.perform(auth(get("/v1/onboarding/documents/" + docId + "/file"), other)).andExpect(status().isNotFound());
        mvc.perform(auth(delete("/v1/onboarding/documents/" + docId), b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(0));
    }

    @Test
    void liveKeysWaitForApprovalAndThenForTwoFactor() throws Exception {
        Business b = signup();
        String live = "{\"name\":\"Prod\",\"type\":\"secret\",\"livemode\":true}";
        mvc.perform(auth(post("/v1/api_keys"), b).contentType(MediaType.APPLICATION_JSON).content(live))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("merchant_not_active"));
        jdbc.sql("UPDATE merchants SET status = 'ACTIVE' WHERE id = :m").param("m", b.merchantId()).update();
        mvc.perform(auth(post("/v1/api_keys"), b).contentType(MediaType.APPLICATION_JSON).content(live))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("two_factor_required"));
    }
}
