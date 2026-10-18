package dev.cutpay.bnplsim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "cutpay.bnpl-sim.splitnow-latency=PT0S",
        "cutpay.bnpl-sim.lendo-decision-delay=PT0.2S",
        "cutpay.bnpl-sim.kreditko-review-delay=PT0.2S",
        "cutpay.bnpl-sim.paylatereu-timeout-rate=0",
        "cutpay.bnpl-sim.timeout-hang=PT0.1S"})
@AutoConfigureMockMvc
class BnplSimTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @AfterEach
    void startOver() throws Exception {
        mvc.perform(post("/admin/reset")).andExpect(status().isOk());
    }

    private String field(ResultActions result, String name) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get(name).asString();
    }

    private ResultActions json(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // --- SplitNow ----------------------------------------------------------------------------------------------

    @Test
    void splitNowQuotesThreeAndFourEqualInstalmentsWithAFeeForTheMerchant() throws Exception {
        json("/splitnow/v1/quotes", "{\"amount_cents\":30000,\"currency\":\"EUR\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(2))
                .andExpect(jsonPath("$.plans[0].instalments").value(3))
                .andExpect(jsonPath("$.plans[0].instalment_cents[0]").value(10000))
                .andExpect(jsonPath("$.plans[0].merchant_fee_cents").value(1050))
                .andExpect(jsonPath("$.plans[1].instalment_cents.length()").value(4));
        // 100.01 split in three: the extra cent goes to the first payment, and the parts add up.
        json("/splitnow/v1/quotes", "{\"amount_cents\":10001,\"currency\":\"EUR\"}")
                .andExpect(jsonPath("$.plans[0].instalment_cents[0]").value(3334))
                .andExpect(jsonPath("$.plans[0].instalment_cents[2]").value(3333));
    }

    @Test
    void splitNowRefusesAmountsOutsideItsLimitsAndOtherCurrencies() throws Exception {
        json("/splitnow/v1/quotes", "{\"amount_cents\":4999,\"currency\":\"EUR\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.error.type").value("amount_out_of_range"));
        json("/splitnow/v1/quotes", "{\"amount_cents\":150001,\"currency\":\"EUR\"}").andExpect(status().isUnprocessableContent());
        json("/splitnow/v1/quotes", "{\"amount_cents\":10000,\"currency\":\"USD\"}")
                .andExpect(jsonPath("$.error.type").value("unsupported_currency"));
    }

    @Test
    void splitNowApprovesAtOnceUnlessTheEmailIsBlockedAndAllowsCancelAndRefundsWithinLimits() throws Exception {
        String ok = "{\"amount_cents\":30000,\"currency\":\"EUR\",\"instalments\":4,\"customer\":{\"email\":\"anna@example.com\"},\"reference\":\"pay_1\"}";
        ResultActions created = json("/splitnow/v1/checkouts", ok).andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("approved")).andExpect(jsonPath("$.merchant_fee_cents").value(1050))
                .andExpect(jsonPath("$.schedule.length()").value(4)).andExpect(jsonPath("$.schedule[1].due_in_days").value(14));
        String id = field(created, "id");

        json("/splitnow/v1/checkouts/" + id + "/refunds", "{\"amount_cents\":10000}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.refunded_cents").value(10000));
        json("/splitnow/v1/checkouts/" + id + "/refunds", "{\"amount_cents\":20001}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.error.type").value("refund_too_large"));
        // Refunded checkouts cannot be canceled any more.
        mvc.perform(post("/splitnow/v1/checkouts/" + id + "/cancel")).andExpect(status().isUnprocessableContent());

        String fresh = field(json("/splitnow/v1/checkouts", ok), "id");
        mvc.perform(post("/splitnow/v1/checkouts/" + fresh + "/cancel")).andExpect(jsonPath("$.state").value("canceled"));
        mvc.perform(get("/splitnow/v1/checkouts/" + fresh)).andExpect(jsonPath("$.state").value("canceled"));

        json("/splitnow/v1/checkouts", ok.replace("anna@example.com", "fraud@example.com")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("declined")).andExpect(jsonPath("$.decline_reason").value("risk_check_failed"));
        mvc.perform(get("/splitnow/v1/checkouts/nope")).andExpect(status().isNotFound());
    }

    // --- Lendo -------------------------------------------------------------------------------------------------

    private static LocalDate birthdate(boolean approved) {
        for (LocalDate date = LocalDate.of(1985, 1, 1); ; date = date.plusDays(1)) {
            if ((LendoController.score(date) >= LendoController.MIN_SCORE) == approved) {
                return date;
            }
        }
    }

    @Test
    void lendoOffersUseDecimalEurosAndAnAnnuityThatAddsUp() throws Exception {
        mvc.perform(get("/lendo/api/offers").param("amount", "1200.00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offers.length()").value(3))
                .andExpect(jsonPath("$.offers[1].months").value(12))
                .andExpect(jsonPath("$.offers[1].apr").value(9.9))
                .andExpect(jsonPath("$.offers[1].merchant_fee").value("34.80"));
        var payments = Instalments.annuity(120_000, 12, LendoController.APR);
        assertThat(payments).hasSize(12).allMatch(p -> p.equals(payments.get(0)));
        assertThat(Instalments.sum(payments)).isBetween(120_000L, 130_000L);
        mvc.perform(get("/lendo/api/offers").param("amount", "99.99")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("amount_out_of_range"));
        mvc.perform(get("/lendo/api/offers").param("amount", "12.345")).andExpect(jsonPath("$.error").value("invalid_amount"));
    }

    @Test
    void lendoAnswersLaterWithASignedFormEncodedCallbackAndCanBePolled() throws Exception {
        BlockingQueue<String[]> received = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/callback", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new String[] {body, exchange.getRequestHeaders().getFirst("X-Lendo-Signature")});
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String callback = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
            ResultActions accepted = mvc.perform(post("/lendo/api/applications").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("amount", "600.00").param("months", "12").param("birthdate", birthdate(true).toString())
                    .param("email", "anna@example.com").param("reference", "pay_9").param("callback_url", callback));
            accepted.andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("processing"));
            String id = field(accepted, "application_id");

            String[] callbackReceived = received.poll(5, TimeUnit.SECONDS);
            assertThat(callbackReceived).isNotNull();
            assertThat(callbackReceived[0]).contains("application_id=" + id).contains("outcome=accepted").contains("reference=pay_9");
            assertThat(callbackReceived[1]).isEqualTo(Callbacks.sign("lendo_test_secret", callbackReceived[0]));
            mvc.perform(get("/lendo/api/applications/" + id)).andExpect(jsonPath("$.status").value("accepted"));

            mvc.perform(post("/lendo/api/applications/" + id + "/refund").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("amount", "100.00")).andExpect(status().isCreated()).andExpect(jsonPath("$.refunded").value("100.00"));
            mvc.perform(post("/lendo/api/applications/" + id + "/refund").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("amount", "500.01")).andExpect(jsonPath("$.error").value("refund_too_large"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lendoRejectsALowScoreAndAMinorAndCancelsWhileProcessing() throws Exception {
        ResultActions low = mvc.perform(post("/lendo/api/applications").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", "600.00").param("months", "6").param("birthdate", birthdate(false).toString())
                .param("email", "boris@example.com"));
        String id = field(low, "application_id");
        awaitStatus("/lendo/api/applications/" + id, "rejected");

        String minor = LocalDate.now().minusYears(17).toString();
        ResultActions young = mvc.perform(post("/lendo/api/applications").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", "600.00").param("months", "6").param("birthdate", minor).param("email", "kid@example.com"));
        awaitStatus("/lendo/api/applications/" + field(young, "application_id"), "rejected");

        mvc.perform(put("/admin/providers/lendo").contentType(MediaType.APPLICATION_JSON).content("{\"decision_delay_ms\":60000}"))
                .andExpect(status().isOk());
        String slow = field(mvc.perform(post("/lendo/api/applications").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", "600.00").param("months", "6").param("birthdate", birthdate(true).toString())
                .param("email", "anna@example.com")), "application_id");
        mvc.perform(post("/lendo/api/applications/" + slow + "/cancel")).andExpect(jsonPath("$.status").value("canceled"));
        mvc.perform(post("/lendo/api/applications").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", "600.00").param("months", "7").param("birthdate", "1990-01-01").param("email", "a@example.com"))
                .andExpect(jsonPath("$.error").value("invalid_term"));
    }

    private void awaitStatus(String path, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            last = field(mvc.perform(get(path)), "status");
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(last).isEqualTo(expected);
    }

    // --- KreditKo ----------------------------------------------------------------------------------------------

    private static String loan(long amount, int term, String nationalId, long income) {
        return """
                {"amountMinor":%d,"currency":"EUR","termMonths":%d,"reference":"pay_7",
                 "applicant":{"nationalId":%s,"monthlyIncomeMinor":%d,"email":"anna@example.com"}}"""
                .formatted(amount, term, nationalId == null ? "null" : "\"" + nationalId + "\"", income);
    }

    @Test
    void kreditKoNeedsTheNationalIdAndIncomeAndPricesTermsInCamelCase() throws Exception {
        json("/kreditko/loans", loan(100_000, 12, null, 250_000)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.problems[0].field").value("applicant.nationalId"));
        json("/kreditko/loans", loan(100_000, 12, "AB12", 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.problems.length()").value(2));
        json("/kreditko/loans", loan(19_999, 12, "AB123456", 250_000)).andExpect(jsonPath("$.code").value("AMOUNT_OUT_OF_RANGE"));
        json("/kreditko/loans", loan(100_000, 2, "AB123456", 250_000)).andExpect(jsonPath("$.code").value("TERM_OUT_OF_RANGE"));
        json("/kreditko/simulations", "{\"amountMinor\":100000,\"termMonths\":12}").andExpect(status().isOk())
                .andExpect(jsonPath("$.aprPercent").value(12.9)).andExpect(jsonPath("$.feeMinor").value(4500))
                .andExpect(jsonPath("$.monthlyMinor").value(Instalments.annuity(100_000, 12, 12.9).get(0)));
    }

    @Test
    void kreditKoDecidesSmallLoansAtOnceByIncomeAndSendsBigOnesToReview() throws Exception {
        json("/kreditko/loans", loan(100_000, 12, "AB123456", 250_000)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        json("/kreditko/loans", loan(100_000, 12, "AB123456", 20_000)).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("insufficient_income"));

        String id = field(json("/kreditko/loans", loan(900_000, 24, "AB123456", 2_000_000)), "loanId");
        mvc.perform(get("/kreditko/loans/" + id)).andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        // A person resolves it before the review would finish by itself.
        mvc.perform(put("/admin/providers/kreditko").contentType(MediaType.APPLICATION_JSON).content("{\"decision_delay_ms\":60000}"));
        String manual = field(json("/kreditko/loans", loan(900_000, 24, "AB123456", 2_000_000)), "loanId");
        json("/admin/kreditko/loans/" + manual + "/decision", "{\"approve\":false}").andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("declined_by_reviewer"));
        json("/admin/kreditko/loans/" + manual + "/decision", "{\"approve\":true}").andExpect(status().isConflict());
        // The first one was created with the short delay and decides by itself, on its income.
        awaitStatus("/kreditko/loans/" + id, "APPROVED");
    }

    @Test
    void kreditKoRefundsAnApprovedLoanUpToItsAmountAndCancelsOnlyOpenOnes() throws Exception {
        String id = field(json("/kreditko/loans", loan(100_000, 12, "AB123456", 250_000)), "loanId");
        json("/kreditko/loans/" + id + "/refunds", "{\"amountMinor\":40000}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundedMinor").value(40000));
        json("/kreditko/loans/" + id + "/refunds", "{\"amountMinor\":60001}").andExpect(status().isUnprocessableContent());
        mvc.perform(post("/kreditko/loans/" + id + "/cancel")).andExpect(jsonPath("$.status").value("CANCELED"));
        mvc.perform(post("/kreditko/loans/" + id + "/cancel")).andExpect(status().isConflict());
        mvc.perform(get("/kreditko/loans/KK-NOPE")).andExpect(status().isNotFound());
    }

    // --- PayLaterEU --------------------------------------------------------------------------------------------

    @Test
    void payLaterEuAnswersEverythingWithOkAndACodeEvenWhenItRefuses() throws Exception {
        json("/paylatereu/api", "{\"action\":\"quote\",\"amount\":250.00,\"currency\":\"EUR\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.payInDays").value(30))
                .andExpect(jsonPath("$.data.merchantFee").value(6.25));
        json("/paylatereu/api", "{\"action\":\"quote\",\"amount\":800.01,\"currency\":\"EUR\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.code").value("AMOUNT_RANGE"));
        json("/paylatereu/api", "{\"action\":\"quote\",\"amount\":19.99}").andExpect(jsonPath("$.code").value("AMOUNT_RANGE"));
        json("/paylatereu/api", "{\"action\":\"dance\"}").andExpect(jsonPath("$.code").value("UNKNOWN_ACTION"));
    }

    @Test
    void payLaterEuAuthorisesThenAllowsStatusRefundAndCancel() throws Exception {
        ResultActions authorised = json("/paylatereu/api",
                "{\"action\":\"authorise\",\"amount\":100.00,\"currency\":\"EUR\",\"customerEmail\":\"anna@example.com\",\"reference\":\"pay_3\"}")
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.data.state").value("AUTHORISED"));
        String id = json.readTree(authorised.andReturn().getResponse().getContentAsString()).get("data").get("authId").asString();

        json("/paylatereu/api", "{\"action\":\"refund\",\"authId\":\"" + id + "\",\"amount\":30.00}").andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.refunded").value(30.00));
        json("/paylatereu/api", "{\"action\":\"refund\",\"authId\":\"" + id + "\",\"amount\":70.01}")
                .andExpect(jsonPath("$.code").value("REFUND_TOO_LARGE"));
        json("/paylatereu/api", "{\"action\":\"cancel\",\"authId\":\"" + id + "\"}").andExpect(jsonPath("$.code").value("NOT_CANCELABLE"));
        json("/paylatereu/api", "{\"action\":\"status\",\"authId\":\"" + id + "\"}").andExpect(jsonPath("$.data.state").value("AUTHORISED"));
        json("/paylatereu/api", "{\"action\":\"status\",\"authId\":\"PLnope\"}").andExpect(jsonPath("$.code").value("UNKNOWN_AUTH"));

        json("/paylatereu/api", "{\"action\":\"authorise\",\"amount\":100.00,\"customerEmail\":\"debt@example.com\"}")
                .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.code").value("DECLINED"));
    }

    // --- the demo controls -------------------------------------------------------------------------------------

    @Test
    void anOutageAnswers503AndAHangingProviderAnswers504UntilReset() throws Exception {
        mvc.perform(put("/admin/providers/splitnow").contentType(MediaType.APPLICATION_JSON).content("{\"outage\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outage").value(true));
        json("/splitnow/v1/quotes", "{\"amount_cents\":30000,\"currency\":\"EUR\"}").andExpect(status().isServiceUnavailable());
        // The others carry on.
        json("/paylatereu/api", "{\"action\":\"quote\",\"amount\":100.00}").andExpect(jsonPath("$.ok").value(true));

        mvc.perform(put("/admin/providers/paylatereu").contentType(MediaType.APPLICATION_JSON).content("{\"timeout_rate\":1}"));
        json("/paylatereu/api", "{\"action\":\"quote\",\"amount\":100.00}").andExpect(status().isGatewayTimeout());

        mvc.perform(post("/admin/reset")).andExpect(status().isOk()).andExpect(jsonPath("$.splitnow.outage").value(false))
                .andExpect(jsonPath("$.paylatereu.timeout_rate").value(0));
        json("/splitnow/v1/quotes", "{\"amount_cents\":30000,\"currency\":\"EUR\"}").andExpect(status().isOk());
        mvc.perform(put("/admin/providers/nobody").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
    }
}
