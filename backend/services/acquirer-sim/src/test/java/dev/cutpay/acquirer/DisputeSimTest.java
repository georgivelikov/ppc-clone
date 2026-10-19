package dev.cutpay.acquirer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@SpringBootTest
@AutoConfigureMockMvc
class DisputeSimTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private String tokenize(String number) throws Exception {
        String body = mvc.perform(post("/tokenize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"%s\",\"exp_month\":12,\"exp_year\":2099,\"cvc\":\"123\"}".formatted(number)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asString();
    }

    @Test
    void theDisputedTestCardIsApprovedAndTellsPaymentsWhenItWillBeDisputed() throws Exception {
        String token = tokenize("4000 0000 0000 0259");
        mvc.perform(get("/tokens/" + token)).andExpect(jsonPath("$.chargeback_after_days").value(1));
        mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"amount\":1000,\"currency\":\"EUR\"}".formatted(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved"));
        mvc.perform(get("/tokens/" + tokenize("4242 4242 4242 4242"))).andExpect(jsonPath("$.chargeback_after_days").doesNotExist());
    }

    @Test
    void theNetworkDecidesOnTheWordsOfTheEvidence() throws Exception {
        evaluate("{\"dispute_id\":\"dp_1\",\"evidence\":\"Parcel signed for. WINNING_EVIDENCE attached\"}", "won", "received");
        evaluate("{\"dispute_id\":\"dp_2\",\"evidence\":\"Please believe me\"}", "lost", "did not show");
        evaluate("{\"dispute_id\":\"dp_3\"}", "lost", "No evidence");
        evaluate("{\"dispute_id\":\"dp_4\",\"files\":2}", "lost", "did not show");
    }

    private void evaluate(String body, String outcome, String reasonPart) throws Exception {
        mvc.perform(post("/disputes/evaluate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(outcome))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString(reasonPart)));
    }
}
