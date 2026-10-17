package dev.cutpay.payouts.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cutpay.payouts.payout.PayoutService.Group;
import dev.cutpay.web.SimulatedClock;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Payouts against a real Postgres and Kafka. The background jobs are slowed to once an hour so the tests
 * call the same service methods themselves and stay deterministic.
 */
@SpringBootTest(properties = {
        "cutpay.outbox.poll-interval=PT0.2S",
        "cutpay.payouts.job-poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class PayoutsFlowIT {

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

    private static final String WORKING_IBAN = "BG80BNBG96611020345678";
    private static final String CLOSED_IBAN = "DE94370400440532010000";

    @Autowired
    PayoutService payouts;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    @Autowired
    MockMvc mvc;

    private String merchant;
    private Group group;

    @BeforeEach
    void freshMerchant() {
        merchant = "mer_" + UUID.randomUUID().toString().replace("-", "");
        group = new Group(merchant, false, "EUR");
        payouts.onMerchantCreated("evt_" + UUID.randomUUID(), merchant);
    }

    private void fundsAvailable(long amount) {
        String entry = "je_" + UUID.randomUUID().toString().replace("-", "");
        payouts.onFundsAvailable("evt_" + UUID.randomUUID(), merchant, false, "EUR", entry, "pay_" + entry, amount);
    }

    private void addBankAccount(String iban) throws Exception {
        mvc.perform(post("/v1/bank_accounts").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"iban\":\"" + iban + "\",\"holder_name\":\"Rose Cafe\",\"currency\":\"EUR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.iban").doesNotExist()) // the full IBAN never leaves the service
                .andExpect(jsonPath("$.iban_last4").value(iban.substring(iban.length() - 4)));
    }

    private void advance(Duration amount) {
        ((SimulatedClock) clock).advance(amount);
    }

    private RequestPostProcessor asMerchant() {
        return jwt().jwt(j -> j.claim("mid", merchant).claim("role", "owner"));
    }

    private String onlyPayoutStatus() {
        return jdbc.sql("SELECT status FROM payouts WHERE merchant_id = :m").param("m", merchant)
                .query(String.class).single();
    }

    private long payoutCount() {
        return jdbc.sql("SELECT COUNT(*) FROM payouts WHERE merchant_id = :m").param("m", merchant)
                .query(Long.class).single();
    }

    private long outboxEvents(String type) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE topic = 'payout.events' AND event_type = :t "
                        + "AND event_key IN (SELECT id FROM payouts WHERE merchant_id = :m)").param("t", type).param("m", merchant).query(Long.class).single();
    }

    @Test
    void availableFundsArePaidOutOnceABankAccountExists() throws Exception {
        fundsAvailable(9_861);

        // No bank account yet: the money waits.
        assertThat(payouts.createScheduledPayout(group)).isEmpty();
        assertThat(payoutCount()).isZero();

        addBankAccount(WORKING_IBAN);
        var created = payouts.createScheduledPayout(group);

        assertThat(created).isPresent();
        assertThat(created.get().amount()).isEqualTo(9_861);
        assertThat(onlyPayoutStatus()).isEqualTo("in_transit");
        assertThat(outboxEvents("PayoutCreated")).isEqualTo(1);

        advance(Duration.ofMinutes(2)); // past the fake bank's delay
        assertThat(payouts.settleDuePayouts(10)).isPositive(); // also settles other tests' leftovers

        assertThat(onlyPayoutStatus()).isEqualTo("paid");
        assertThat(outboxEvents("PayoutPaid")).isEqualTo(1);
    }

    @Test
    void aClosedBankAccountFailsThePayoutAndReportsWhy() throws Exception {
        fundsAvailable(5_000);
        addBankAccount(CLOSED_IBAN);
        payouts.createScheduledPayout(group);

        advance(Duration.ofMinutes(2));
        payouts.settleDuePayouts(10);

        assertThat(onlyPayoutStatus()).isEqualTo("failed");
        assertThat(outboxEvents("PayoutFailed")).isEqualTo(1);
        mvc.perform(get("/v1/payouts").with(asMerchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("failed"))
                .andExpect(jsonPath("$.data[0].failure_code").value("account_closed"));
    }

    @Test
    void aScheduledPayoutRunsOncePerDay() throws Exception {
        addBankAccount(WORKING_IBAN);
        fundsAvailable(1_000);
        assertThat(payouts.createScheduledPayout(group)).isPresent();

        fundsAvailable(2_000);
        assertThat(payouts.createScheduledPayout(group)).isEmpty();
        assertThat(payoutCount()).isEqualTo(1);

        advance(Duration.ofDays(1));
        var next = payouts.createScheduledPayout(group);
        assertThat(next).isPresent();
        assertThat(next.get().amount()).isEqualTo(2_000);
    }

    @Test
    void redeliveredFundsAvailableEventsAreCountedOnce() throws Exception {
        String entry = "je_dup";
        for (int i = 0; i < 2; i++) {
            payouts.onFundsAvailable("evt_same_" + merchant, merchant, false, "EUR", entry + merchant, "pay_x", 700);
        }
        addBankAccount(WORKING_IBAN);

        assertThat(payouts.createScheduledPayout(group).orElseThrow().amount()).isEqualTo(700);
    }

    @Test
    void aNegativeEntryReducesTheNextPayout() throws Exception {
        addBankAccount(WORKING_IBAN);
        fundsAvailable(10_000);
        fundsAvailable(-3_000);

        assertThat(payouts.createScheduledPayout(group).orElseThrow().amount()).isEqualTo(7_000);
    }

    @Test
    void anInstantPayoutChargesOnePercentOnTopAndRequiresFunds() throws Exception {
        addBankAccount(WORKING_IBAN);

        mvc.perform(post("/v1/payouts").with(asMerchant()))
                .andExpect(status().is4xxClientError());

        fundsAvailable(10_000);
        mvc.perform(get("/v1/payouts/preview").with(asMerchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(10_000))
                .andExpect(jsonPath("$.fee").value(100))
                .andExpect(jsonPath("$.net").value(9_900));

        mvc.perform(post("/v1/payouts").with(asMerchant()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(9_900))
                .andExpect(jsonPath("$.fee").value(100))
                .andExpect(jsonPath("$.kind").value("instant"));
    }

    @Test
    void reconciliationShowsThatTheItemsAddUpToThePayout() throws Exception {
        addBankAccount(WORKING_IBAN);
        fundsAvailable(4_000);
        fundsAvailable(6_000);
        String id = payouts.createScheduledPayout(group).orElseThrow().payoutId();

        mvc.perform(get("/v1/payouts/" + id + "/reconciliation").with(asMerchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item_count").value(2))
                .andExpect(jsonPath("$.items_total").value(10_000))
                .andExpect(jsonPath("$.balanced").value(true));
    }

    @Test
    void merchantsOnlySeeTheirOwnPayoutsAndEndpointsNeedAToken() throws Exception {
        addBankAccount(WORKING_IBAN);
        fundsAvailable(1_000);
        payouts.createScheduledPayout(group);

        mvc.perform(get("/v1/payouts").with(jwt().jwt(j -> j.claim("mid", "mer_other").claim("role", "owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/v1/payouts")).andExpect(status().isUnauthorized());
    }
}
