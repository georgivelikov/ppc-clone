package dev.cutpay.acquirer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "cutpay.acquirer.timeout-card-delay=PT0.1S")
@AutoConfigureMockMvc
class AcquirerSimTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private String tokenize(String pan) throws Exception {
        String body = mvc.perform(post("/tokenize").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"number":"%s","exp_month":12,"exp_year":2099,"cvc":"123"}""".formatted(pan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.startsWith("tok_")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asString();
    }

    private org.springframework.test.web.servlet.ResultActions authorize(String token) throws Exception {
        return mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","amount":1000,"currency":"EUR"}""".formatted(token)));
    }

    @Test
    void approvesVisaAndMastercardTestCards() throws Exception {
        authorize(tokenize("4242424242424242")).andExpect(jsonPath("$.status").value("approved"));
        authorize(tokenize("5555555555554444")).andExpect(jsonPath("$.status").value("approved"));
    }

    @Test
    void classifiesCardsByBin() throws Exception {
        String[][] cases = {
                {"4242424242424242", "consumer", "eea"},
                {"4000010000000001", "business", "eea"},
                {"4000020000000000", "consumer", "non_eea"},
                {"4000030000000009", "business", "non_eea"}};
        for (String[] c : cases) {
            String token = tokenize(c[0]);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/tokens/" + token))
                    .andExpect(jsonPath("$.card_type").value(c[1]))
                    .andExpect(jsonPath("$.card_region").value(c[2]));
            authorize(token).andExpect(jsonPath("$.status").value("approved"));
        }
    }

    @Test
    void declinesWithTheDocumentedCodes() throws Exception {
        authorize(tokenize("4000000000000002")).andExpect(jsonPath("$.decline_code").value("card_declined"));
        authorize(tokenize("4000000000009995")).andExpect(jsonPath("$.decline_code").value("insufficient_funds"));
        authorize(tokenize("4000000000000069")).andExpect(jsonPath("$.decline_code").value("expired_card"));
    }

    @Test
    void theTimeoutCardIsAcceptedByTheVaultAndAnswersSlowly() throws Exception {
        // The documented number must pass the card checksum, or the vault rejects it before the delay can happen.
        String token = tokenize("4000000000007775");
        long started = System.nanoTime();
        authorize(token).andExpect(status().isOk());
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(java.time.Duration.ofMillis(100));
    }

    @Test
    void failsWithServerErrorOnTheProcessingErrorCard() throws Exception {
        authorize(tokenize("4000000000000119")).andExpect(status().isBadGateway());
    }

    @Test
    void rejectsInvalidCardNumbers() throws Exception {
        mvc.perform(post("/tokenize").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"number":"4242424242424241","exp_month":12,"exp_year":2099,"cvc":"123"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_card_number"));
    }

    @Test
    void completesA3dsChallengeOnlyWithTheRightCode() throws Exception {
        String body = authorize(tokenize("4000000000003220"))
                .andExpect(jsonPath("$.status").value("requires_action"))
                .andReturn().getResponse().getContentAsString();
        String ref = json.readTree(body).get("acquirer_ref").asString();

        // No payments service is running, so delivering the result fails with 502 after the challenge is resolved.
        mvc.perform(post("/3ds/challenge/%s/complete".formatted(ref)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"1234\"}"))
                .andExpect(status().isBadGateway());
        mvc.perform(post("/3ds/challenge/%s/complete".formatted(ref)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"1234\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMailOrTelephoneOrderSkipsThe3dsChallenge() throws Exception {
        String token = tokenize("4000000000003220");
        mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","amount":1000,"currency":"EUR","channel":"moto"}""".formatted(token)))
                .andExpect(jsonPath("$.status").value("approved"));
    }
}
