package dev.cutpay.kycsim;

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

@SpringBootTest(properties = "cutpay.kyc-sim.verification-delay=PT0S")
@AutoConfigureMockMvc
class KycSimTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private String verify(String documentNumber) throws Exception {
        String started = mvc.perform(post("/verifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"document_number\":\"%s\",\"document_type\":\"id_card\",\"full_name\":\"Ivan Petrov\"}".formatted(documentNumber)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("pending"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(started).get("id").asString();
    }

    @Test
    void theRegisterAnswersWithTheSameMadeUpCompanyForTheSameNumber() throws Exception {
        String first = mvc.perform(get("/registry/BG/123456789")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active")).andExpect(jsonPath("$.representatives.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String again = mvc.perform(get("/registry/bg/123456789")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(again).isEqualTo(first);
    }

    @Test
    void numbersMustFitTheCountryAndSomeAreMissingOrDissolved() throws Exception {
        mvc.perform(get("/registry/BG/12345")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_registration_number"));
        mvc.perform(get("/registry/PL/1234567890")).andExpect(status().isOk());
        mvc.perform(get("/registry/RO/14399840")).andExpect(status().isOk());
        mvc.perform(get("/registry/BG/123456000")).andExpect(status().isNotFound());
        mvc.perform(get("/registry/BG/123456999")).andExpect(jsonPath("$.status").value("dissolved"));
        mvc.perform(get("/registry/FR/12345678")).andExpect(status().isNotFound());
    }

    @Test
    void documentsAreDecidedByTheirLastDigit() throws Exception {
        mvc.perform(get("/verifications/" + verify("AB123456"))).andExpect(jsonPath("$.status").value("approved"));
        mvc.perform(get("/verifications/" + verify("AB123450"))).andExpect(jsonPath("$.status").value("rejected"));
        mvc.perform(get("/verifications/" + verify("AB123459"))).andExpect(jsonPath("$.status").value("needs_more_info"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("sharper")));
        mvc.perform(get("/verifications/vrf_unknown")).andExpect(status().isNotFound());
    }

    @Test
    void screeningFindsOnlyTheNamesOnItsTinyLists() throws Exception {
        mvc.perform(post("/screening").contentType(MediaType.APPLICATION_JSON).content("{\"full_name\":\"Ivan Sanctionov\"}"))
                .andExpect(jsonPath("$.hits[0].list").value("sanctions"));
        mvc.perform(post("/screening").contentType(MediaType.APPLICATION_JSON).content("{\"full_name\":\"maria MINISTER\"}"))
                .andExpect(jsonPath("$.hits[0].list").value("pep"));
        mvc.perform(post("/screening").contentType(MediaType.APPLICATION_JSON).content("{\"full_name\":\"Ivan Petrov\"}"))
                .andExpect(jsonPath("$.hits.length()").value(0));
    }
}
