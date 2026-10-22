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
    void theIbanIsStoredEncryptedAndTheListStillShowsTheLastFourDigits() throws Exception {
        addBankAccount(WORKING_IBAN);

        String stored = jdbc.sql("SELECT iban FROM bank_accounts WHERE merchant_id = :m").param("m", merchant)
                .query(String.class).single();
        assertThat(stored).startsWith("local:v1:").doesNotContain(WORKING_IBAN).doesNotContain("5678");

        mvc.perform(get("/v1/bank_accounts").with(asMerchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].country").value("BG"))
                .andExpect(jsonPath("$.data[0].iban_last4").value("5678"));
    }

    @Test
    void aClosedAccountIsStillRecognisedThroughTheEncryption() throws Exception {
        fundsAvailable(5_000);
        addBankAccount(CLOSED_IBAN);
        assertThat(payouts.createScheduledPayout(group)).isPresent();
        advance(Duration.ofMinutes(2));
        payouts.settleDuePayouts(10);
        assertThat(onlyPayoutStatus()).isEqualTo("failed");
    }

    @Test
    void aFrozenBusinessKeepsItsMoneyUntilTheHoldIsLifted() throws Exception {
        fundsAvailable(5_000);
        addBankAccount(WORKING_IBAN);
        payouts.onFreezeChanged("evt_" + UUID.randomUUID(), merchant, true);
        payouts.onFreezeChanged("evt_" + UUID.randomUUID(), merchant, true); // twice is the same as once

        assertThat(payouts.isFrozen(merchant)).isTrue();
        assertThat(payouts.createScheduledPayout(group)).isEmpty();
        mvc.perform(post("/v1/payouts").with(asMerchant()).param("currency", "EUR"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("payouts_frozen"));
        assertThat(payoutCount()).isZero();

        payouts.onFreezeChanged("evt_" + UUID.randomUUID(), merchant, false);
        assertThat(payouts.isFrozen(merchant)).isFalse();
        assertThat(payouts.createScheduledPayout(group)).isPresent(); // the money waited and now goes
        assertThat(payoutCount()).isEqualTo(1);
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
    void aConnectedAccountIsPaidOutToItsOwnIbanAndThePlatformNeverSeesThatBankAccount() throws Exception {
        String account = "acct_" + UUID.randomUUID().toString().replace("-", "");
        payouts.onConnectedAccount("evt_" + UUID.randomUUID(), account, false, WORKING_IBAN, "Pizza Palace", "EUR", false);
        String entry = "je_" + UUID.randomUUID().toString().replace("-", "");
        payouts.onFundsAvailable("evt_" + UUID.randomUUID(), account, false, "EUR", entry, "pay_" + entry, 6_000);

        var created = payouts.createScheduledPayout(new Group(account, false, "EUR"));

        assertThat(created).isPresent();
        assertThat(created.get().amount()).isEqualTo(6_000);
        assertThat(jdbc.sql("SELECT holder_name FROM bank_accounts WHERE merchant_id = :m AND status = 'active'")
                .param("m", account).query(String.class).single()).isEqualTo("Pizza Palace");
        // The platform lists only its own bank accounts.
        mvc.perform(get("/v1/bank_accounts").with(asMerchant())).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void whenTheIbanOfAConnectedAccountChangesFuturePayoutsGoToTheNewOne() throws Exception {
        String account = "acct_" + UUID.randomUUID().toString().replace("-", "");
        payouts.onConnectedAccount("evt_" + UUID.randomUUID(), account, false, WORKING_IBAN, "Pizza Palace", "EUR", false);
        payouts.onConnectedAccount("evt_" + UUID.randomUUID(), account, false, "DE89370400440532013000", "Pizza Palace GmbH",
                "EUR", true);

        assertThat(jdbc.sql("SELECT holder_name FROM bank_accounts WHERE merchant_id = :m AND status = 'active'")
                .param("m", account).query(String.class).list()).containsExactly("Pizza Palace GmbH");
        assertThat(jdbc.sql("SELECT count(*) FROM bank_accounts WHERE merchant_id = :m").param("m", account)
                .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void aConnectedAccountEventDeliveredTwiceChangesNothing() {
        String account = "acct_" + UUID.randomUUID().toString().replace("-", "");
        String event = "evt_" + UUID.randomUUID();
        payouts.onConnectedAccount(event, account, false, WORKING_IBAN, "Pizza Palace", "EUR", false);
        payouts.onConnectedAccount(event, account, false, WORKING_IBAN, "Pizza Palace", "EUR", false);
        assertThat(jdbc.sql("SELECT count(*) FROM bank_accounts WHERE merchant_id = :m").param("m", account)
                .query(Long.class).single()).isEqualTo(1L);
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
    void anApiKeyOnlySeesPayoutsAndBankAccountsOfItsOwnMode() throws Exception {
        addBankAccount(WORKING_IBAN);
        fundsAvailable(1_000);
        payouts.createScheduledPayout(group);
        String payoutId = payoutIds().get(0);
        RequestPostProcessor testKey = jwt().jwt(j -> j.claim("mid", merchant).claim("role", "api")
                .claim("livemode", false));
        RequestPostProcessor liveKey = jwt().jwt(j -> j.claim("mid", merchant).claim("role", "api")
                .claim("livemode", true));

        mvc.perform(get("/v1/payouts").param("livemode", "true").with(testKey))
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/v1/payouts").param("livemode", "false").with(liveKey))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/v1/payouts/" + payoutId).with(liveKey)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/payouts/" + payoutId).with(testKey)).andExpect(status().isOk());
        mvc.perform(get("/v1/bank_accounts").param("livemode", "false").with(liveKey))
                .andExpect(jsonPath("$.data").isEmpty());
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

    private java.util.List<String> payoutIds() {
        return jdbc.sql("SELECT id FROM payouts WHERE merchant_id = :m").param("m", merchant)
                .query(String.class).list();
    }
}
