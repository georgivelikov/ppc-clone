package dev.cutpay.acquirer;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "cutpay.acquirer.blik-delay=PT60S")
@AutoConfigureMockMvc
class WalletAndBlikTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private ResultActions walletAuth(String wallet, String card, boolean authenticated) throws Exception {
        return mvc.perform(post("/wallet/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"wallet_type":"%s","card_id":"%s","authenticated":%s}""".formatted(wallet, card, authenticated)));
    }

    private String walletToken(String wallet, String card) throws Exception {
        String body = walletAuth(wallet, card, true).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asString();
    }

    private ResultActions authorize(String token) throws Exception {
        return mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","amount":1000,"currency":"EUR"}""".formatted(token)));
    }

    @Test
    void theWalletOffersItsCardsAndHandsOutADeviceTokenWithACryptogram() throws Exception {
        mvc.perform(get("/wallet/cards")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        walletAuth("apple_pay", "visa", true)
                .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.startsWith("tok_")))
                .andExpect(jsonPath("$.dpan").value(org.hamcrest.Matchers.matchesPattern("4999[0-9]{12}")))
                .andExpect(jsonPath("$.cryptogram").isNotEmpty())
                .andExpect(jsonPath("$.eci").value("05"))
                .andExpect(jsonPath("$.last4").value("4242"));
        walletAuth("google_pay", "mastercard", true).andExpect(jsonPath("$.eci").value("02"));
    }

    @Test
    void aWalletNeedsTheCustomerToConfirmAndKnowsOnlyItsOwnCards() throws Exception {
        walletAuth("apple_pay", "visa", false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("wallet_not_authenticated"));
        walletAuth("apple_pay", "nope", true).andExpect(status().isBadRequest());
        walletAuth("samsung_pay", "visa", true).andExpect(status().isBadRequest());
    }

    @Test
    void aWalletTokenDescribesItselfAsAWalletAndSkips3dsEvenForACardThatAsksForIt() throws Exception {
        String token = walletToken("google_pay", "secure");
        mvc.perform(get("/tokens/" + token)).andExpect(jsonPath("$.wallet_type").value("google_pay"))
                .andExpect(jsonPath("$.last4").value("3220"));
        authorize(token).andExpect(jsonPath("$.status").value("approved"));
    }

    private ResultActions blik(String code, long amount, String currency) throws Exception {
        return mvc.perform(post("/blik/charge").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"%s","amount":%d,"currency":"%s","reference":"pay_1"}""".formatted(code, amount, currency)));
    }

    @Test
    void theApprovedCodeWaitsForTheBankApp() throws Exception {
        String body = blik("777123", 5000, "PLN").andExpect(jsonPath("$.status").value("requires_action"))
                .andReturn().getResponse().getContentAsString();
        String ref = json.readTree(body).get("acquirer_ref").asString();
        mvc.perform(get("/blik/requests")).andExpect(jsonPath("$[?(@.id=='%s')].amount".formatted(ref)).value(5000));

        // Answering delivers the result to payments, which is not running here: the request is still taken off the list.
        mvc.perform(post("/blik/requests/%s/approve".formatted(ref))).andExpect(status().isBadGateway());
        mvc.perform(post("/blik/requests/%s/approve".formatted(ref))).andExpect(status().isNotFound());
        // What was approved can be refunded.
        mvc.perform(post("/refund").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acquirer_ref\":\"%s\",\"amount\":5000}".formatted(ref))).andExpect(status().isOk());
    }

    @Test
    void aRejectedRequestCannotBeRefunded() throws Exception {
        String body = blik("777123", 5000, "PLN").andReturn().getResponse().getContentAsString();
        String ref = json.readTree(body).get("acquirer_ref").asString();
        mvc.perform(post("/blik/requests/%s/reject".formatted(ref))).andExpect(status().isBadGateway());
        mvc.perform(post("/refund").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acquirer_ref\":\"%s\",\"amount\":1}".formatted(ref))).andExpect(status().isNotFound());
    }

    @Test
    void otherCodesAreDeclinedOrInvalidAndOnlyPlnIsAccepted() throws Exception {
        blik("000000", 5000, "PLN").andExpect(jsonPath("$.status").value("declined"))
                .andExpect(jsonPath("$.decline_code").value("blik_declined"));
        blik("123456", 5000, "PLN").andExpect(jsonPath("$.status").value("invalid_code"));
        blik("12345", 5000, "PLN").andExpect(jsonPath("$.status").value("invalid_code"));
        blik("777123", 5000, "EUR").andExpect(jsonPath("$.decline_code").value("currency_not_supported"));
        assertThat(mvc.perform(get("/sim/blik-app")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("Pretend bank app");
    }
}
