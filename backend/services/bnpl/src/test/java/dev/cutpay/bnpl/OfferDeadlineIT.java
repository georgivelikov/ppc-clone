package dev.cutpay.bnpl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The promise to the customer: offers appear within the deadline (2 seconds, the default) however slow one provider is.
 * It has a context of its own because it is the only test that needs the real deadline; the others use generous
 * timeouts so that a busy machine does not make providers look slow.
 */
@SpringBootTest(properties = {"cutpay.bnpl.provider-timeout=PT1.8S", "cutpay.bnpl.offers-deadline=PT2S",
        "cutpay.bnpl.job-poll-interval=PT1H", "cutpay.outbox.poll-interval=PT0.2S"})
class OfferDeadlineIT extends BnplIntegrationTest {

    private void offers() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk());
    }

    @Test
    void aSlowProviderIsLeftOutAndTheCustomerIsNotKeptWaiting() throws Exception {
        offers(); // the first request in a new context pays for class loading and connections; time the second one

        WORLD.stubFor(get(urlPathEqualTo("/lendo/api/offers")).willReturn(aResponse().withFixedDelay(10_000)
                .withHeader("Content-Type", "application/json").withBody("{\"offers\":[]}")));

        long started = System.nanoTime();
        mvc.perform(MockMvcRequestBuilders.get("/checkout/" + secret + "/bnpl/offers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.offers[?(@.provider == 'lendo')]").isEmpty())
                .andExpect(jsonPath("$.offers[?(@.provider == 'splitnow')]").isNotEmpty())
                .andExpect(jsonPath("$.offers[?(@.provider == 'kreditko')]").isNotEmpty())
                .andExpect(jsonPath("$.offers[?(@.provider == 'paylatereu')]").isNotEmpty())
                .andExpect(jsonPath("$.unavailable.length()").value(1))
                .andExpect(jsonPath("$.unavailable[0].provider").value("lendo"))
                .andExpect(jsonPath("$.unavailable[0].reason").value("did not answer in time"));
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).isLessThan(2_500);
    }
}
