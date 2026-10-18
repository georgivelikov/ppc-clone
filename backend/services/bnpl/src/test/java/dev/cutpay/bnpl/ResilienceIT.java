package dev.cutpay.bnpl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A provider that is down: its circuit opens after a few failures, it is left out of the offers without being called, and
 * after a short wait one probe decides whether it is back. The circuit here waits one second instead of ten.
 */
@SpringBootTest(properties = {"cutpay.bnpl.provider-timeout=PT5S", "cutpay.bnpl.offers-deadline=PT10S",
        "cutpay.bnpl.job-poll-interval=PT1H", "cutpay.outbox.poll-interval=PT0.2S", "cutpay.bnpl.breaker.window=4",
        "cutpay.bnpl.breaker.minimum-calls=3", "cutpay.bnpl.breaker.failure-rate=50", "cutpay.bnpl.breaker.wait-in-open=PT1S",
        "cutpay.bnpl.breaker.probe-calls=1"})
class ResilienceIT extends BnplIntegrationTest {

    private void offers() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk());
    }

    private String circuit(String provider) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/analytics").with(merchant())).andReturn().getResponse().getContentAsString();
        for (var row : new tools.jackson.databind.json.JsonMapper().readTree(body).get("providers")) {
            if (provider.equals(row.get("provider").asString())) {
                return row.get("circuit").asString();
            }
        }
        throw new AssertionError("no such provider " + provider);
    }

    private static void payLaterIsDown() {
        WORLD.stubFor(post(urlEqualTo("/paylatereu/api")).willReturn(aResponse().withStatus(503)));
    }

    private static void payLaterIsBack() {
        json(post("/paylatereu/api"), "{\"ok\":true,\"code\":\"OK\",\"data\":{\"payInDays\":30,\"customerCost\":0.00,\"merchantFee\":7.50}}");
    }

    @Test
    void aProviderThatKeepsFailingIsLeftOutWithoutBeingCalledAndComesBackAfterAProbe() throws Exception {
        payLaterIsDown();
        for (int i = 0; i < 3; i++) {
            mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.unavailable[0].reason").value("is not responding"));
        }
        WORLD.verify(3, postRequestedFor(urlEqualTo("/paylatereu/api")));

        // The circuit is open now: PayLaterEU is not asked, and it is not reported as a failure either, just not offered.
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.offers[?(@.provider == 'paylatereu')]").isEmpty())
                .andExpect(jsonPath("$.offers[?(@.provider == 'splitnow')]").isNotEmpty())
                .andExpect(jsonPath("$.unavailable.length()").value(0));
        WORLD.verify(3, postRequestedFor(urlEqualTo("/paylatereu/api")));
        mvc.perform(MockMvcRequestBuilders.get("/v1/bnpl/routing_decisions/" + paymentId()).with(merchant()))
                .andExpect(jsonPath("$.excluded[?(@.provider == 'paylatereu')].code").value("unavailable"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("PayLaterEU excluded: temporarily unavailable")));
        org.assertj.core.api.Assertions.assertThat(circuit("paylatereu")).isEqualTo("open");

        // After the wait the circuit lets a probe through; the provider is back, so the probe succeeds and the circuit closes.
        payLaterIsBack();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(circuit("paylatereu")).isEqualTo("half_open"));
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.offers[?(@.provider == 'paylatereu')]").isNotEmpty());
        org.assertj.core.api.Assertions.assertThat(circuit("paylatereu")).isEqualTo("closed");
    }

    @Test
    void aFailedProbeOpensTheCircuitAgain() throws Exception {
        payLaterIsDown();
        for (int i = 0; i < 3; i++) {
            offers();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(circuit("paylatereu")).isEqualTo("half_open"));

        offers(); // the probe, and PayLaterEU is still down

        org.assertj.core.api.Assertions.assertThat(circuit("paylatereu")).isEqualTo("open");
    }

    @Test
    void aProviderThatKeepsSayingNoIsWorkingAndItsCircuitStaysClosed() throws Exception {
        json(post("/paylatereu/api"), "{\"ok\":false,\"code\":\"AMOUNT_RANGE\",\"data\":null}");
        for (int i = 0; i < 8; i++) {
            mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.unavailable[0].reason").value("declined to quote"));
        }

        org.assertj.core.api.Assertions.assertThat(circuit("paylatereu")).isEqualTo("closed");
        WORLD.verify(8, postRequestedFor(urlEqualTo("/paylatereu/api")));
    }

    @Test
    void anOpenCircuitStopsApplicationsToThatProviderToo() throws Exception {
        payLaterIsDown();
        for (int i = 0; i < 3; i++) {
            offers();
        }

        mvc.perform(MockMvcRequestBuilders.post("/checkout/" + secret + "/bnpl/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"paylatereu\",\"plan\":\"paylatereu_30\",\"applicant\":{\"email\":\"anna@example.com\"}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("provider_not_available"));
        WORLD.verify(3, postRequestedFor(urlEqualTo("/paylatereu/api")));
    }
}
