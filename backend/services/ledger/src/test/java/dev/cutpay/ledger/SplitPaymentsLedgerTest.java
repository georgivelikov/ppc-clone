package dev.cutpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cutpay.ledger.posting.AccountType;
import dev.cutpay.ledger.posting.LedgerEvents.PaymentSucceeded;
import dev.cutpay.ledger.posting.LedgerEvents.RefundSucceeded;
import dev.cutpay.ledger.posting.LedgerEvents.Transfer;
import dev.cutpay.ledger.posting.LedgerService;
import dev.cutpay.web.SimulatedClock;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A payment shared between a platform and its connected accounts, and the refunds taken back from them. */
@SpringBootTest(properties = {"cutpay.outbox.poll-interval=PT0.2S", "cutpay.ledger.availability-poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class SplitPaymentsLedgerTest {

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

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    @Autowired
    MockMvc mvc;

    private String platform;
    private String shopA;
    private String shopB;
    private String pay;

    @BeforeEach
    void freshParties() {
        platform = id("mer");
        shopA = id("acct");
        shopB = id("acct");
        pay = id("pay");
    }

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** A 100.00 EUR payment from a European consumer card (fee 1.29% + 0.10 = 1.39), shared as given. */
    private void splitPayment(long amount, Transfer... transfers) {
        ledger.onPaymentSucceeded(new PaymentSucceeded(id("evt"), clock.instant(), platform, false, pay, amount, "EUR",
                "consumer", "eea", List.of(transfers)));
    }

    private void refund(long amount) {
        ledger.onRefundSucceeded(new RefundSucceeded(id("evt"), clock.instant(), platform, false, id("re"), pay, amount, "EUR"));
    }

    private long owed(String merchantId, AccountType type) {
        return -jdbc.sql("""
                        SELECT COALESCE(SUM(b.amount), 0) FROM accounts a JOIN balances b ON b.account_id = a.id
                         WHERE a.merchant_id = :merchant AND a.type = :type AND a.livemode = false
                        """)
                .param("merchant", merchantId).param("type", type.wire()).query(Long.class).single();
    }

    private void releaseEverything() {
        ((SimulatedClock) clock).advance(Duration.ofDays(3));
        int released;
        do {
            released = ledger.releaseDueFunds(100);
        } while (released == 100);
    }

    private void assertTrialBalanceIsZero() {
        assertThat(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single()).as("trial balance").isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM accounts a LEFT JOIN balances b ON b.account_id = a.id
                         WHERE COALESCE(b.amount, 0) <>
                               (SELECT COALESCE(SUM(p.amount), 0) FROM postings p WHERE p.account_id = a.id)
                        """).query(Long.class).single()).isZero();
    }

    @Test
    void eachPartyIsCreditedItsShareAndThePlatformKeepsTheRestMinusTheFee() {
        splitPayment(10_000, new Transfer(shopA, 6_000), new Transfer(shopB, 1_000));

        assertThat(owed(shopA, AccountType.MERCHANT_PENDING)).isEqualTo(6_000);
        assertThat(owed(shopB, AccountType.MERCHANT_PENDING)).isEqualTo(1_000);
        // 100.00 - 60.00 - 10.00 - 1.39 fee
        assertThat(owed(platform, AccountType.MERCHANT_PENDING)).isEqualTo(2_861);
        assertTrialBalanceIsZero();
    }

    @Test
    void thePlatformSeesItsPaymentNetOfTransfersAndEachAccountSeesATransfer() {
        splitPayment(10_000, new Transfer(shopA, 6_000));

        var platformRow = jdbc.sql("SELECT gross, fee, net FROM balance_transactions WHERE merchant_id = :m AND type = 'payment'")
                .param("m", platform).query((rs, n) -> List.of(rs.getLong("gross"), rs.getLong("fee"), rs.getLong("net"))).single();
        assertThat(platformRow).containsExactly(10_000L, 139L, 3_861L);
        var accountRow = jdbc.sql("SELECT gross, fee, net, source_id FROM balance_transactions WHERE merchant_id = :m AND type = 'transfer'")
                .param("m", shopA).query((rs, n) -> List.of(rs.getLong("gross"), rs.getLong("fee"), rs.getLong("net"))).single();
        assertThat(accountRow).containsExactly(6_000L, 0L, 6_000L);
    }

    @Test
    void everyPartyIsReleasedOnItsOwnAndOnlyOnce() {
        splitPayment(10_000, new Transfer(shopA, 6_000), new Transfer(shopB, 1_000));
        releaseEverything();
        releaseEverything();

        assertThat(owed(shopA, AccountType.MERCHANT_AVAILABLE)).isEqualTo(6_000);
        assertThat(owed(shopA, AccountType.MERCHANT_PENDING)).isZero();
        assertThat(owed(shopB, AccountType.MERCHANT_AVAILABLE)).isEqualTo(1_000);
        assertThat(owed(platform, AccountType.MERCHANT_AVAILABLE)).isEqualTo(2_861);
        assertTrialBalanceIsZero();
    }

    @Test
    void aPartialRefundIsTakenBackInProportionBeforeRelease() {
        splitPayment(10_000, new Transfer(shopA, 6_000), new Transfer(shopB, 1_000));
        refund(5_000);

        // Half of each transfer goes back, and the platform covers the remaining 15.00.
        assertThat(owed(shopA, AccountType.MERCHANT_PENDING)).isEqualTo(3_000);
        assertThat(owed(shopB, AccountType.MERCHANT_PENDING)).isEqualTo(500);
        assertThat(owed(platform, AccountType.MERCHANT_PENDING)).isEqualTo(2_861 - 1_500);

        releaseEverything();
        assertThat(owed(shopA, AccountType.MERCHANT_AVAILABLE)).isEqualTo(3_000);
        assertThat(owed(shopB, AccountType.MERCHANT_AVAILABLE)).isEqualTo(500);
        assertThat(owed(platform, AccountType.MERCHANT_AVAILABLE)).isEqualTo(1_361);
        assertTrialBalanceIsZero();
    }

    @Test
    void aRefundAfterReleaseMakesEachAccountsAvailableBalanceShrinkAndPayoutsHearOfIt() {
        splitPayment(10_000, new Transfer(shopA, 6_000));
        releaseEverything();
        refund(5_000);

        assertThat(owed(shopA, AccountType.MERCHANT_AVAILABLE)).isEqualTo(3_000);
        assertThat(owed(platform, AccountType.MERCHANT_AVAILABLE)).isEqualTo(3_861 - 2_000);
        // Payouts keeps one row per ledger entry, so the account's row must not share its id with the platform's.
        var rows = jdbc.sql("SELECT event_key, payload FROM outbox WHERE event_type = 'FundsAvailable' ORDER BY id")
                .query((rs, n) -> rs.getString("event_key") + " " + rs.getString("payload")).list();
        assertThat(rows.stream().filter(r -> r.startsWith(shopA) && r.contains("\"amount\":-3000")).toList()).hasSize(1);
        assertThat(rows.stream().filter(r -> r.contains("\"entry_id\":\"") && r.contains("#" + shopA)).toList()).hasSize(1);
        assertTrialBalanceIsZero();
    }

    @Test
    void aFullRefundTakesEverythingBackAndLeavesThePlatformOutOfPocketByTheFee() {
        splitPayment(10_000, new Transfer(shopA, 6_000), new Transfer(shopB, 1_000));
        refund(10_000);

        assertThat(owed(shopA, AccountType.MERCHANT_PENDING)).isZero();
        assertThat(owed(shopB, AccountType.MERCHANT_PENDING)).isZero();
        assertThat(owed(platform, AccountType.MERCHANT_PENDING)).isEqualTo(-139);
        assertTrialBalanceIsZero();
    }

    @Test
    void severalSmallRefundsNeverTakeMoreFromAnAccountThanItReceived() {
        splitPayment(10_000, new Transfer(shopA, 3_333), new Transfer(shopB, 3_333));
        for (int i = 0; i < 20; i++) {
            refund(500);
        }
        // 10,000 was refunded in full: the accounts gave back at most what they got, never a negative balance.
        assertThat(owed(shopA, AccountType.MERCHANT_PENDING)).isGreaterThanOrEqualTo(0);
        assertThat(owed(shopB, AccountType.MERCHANT_PENDING)).isGreaterThanOrEqualTo(0);
        assertThat(owed(shopA, AccountType.MERCHANT_PENDING) + owed(shopB, AccountType.MERCHANT_PENDING)
                + owed(platform, AccountType.MERCHANT_PENDING)).isEqualTo(-139);
        assertTrialBalanceIsZero();
    }

    @Test
    void aSplitThatSendsMoreThanThePaymentIsRefused() {
        assertThatThrownBy(() -> splitPayment(1_000, new Transfer(shopA, 800), new Transfer(shopB, 300)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRedeliveredSplitPaymentIsLedgeredOnce() {
        var event = new PaymentSucceeded(id("evt"), clock.instant(), platform, false, pay, 10_000, "EUR", "consumer", "eea",
                List.of(new Transfer(shopA, 6_000)));
        ledger.onPaymentSucceeded(event);
        ledger.onPaymentSucceeded(event);
        assertThat(owed(shopA, AccountType.MERCHANT_PENDING)).isEqualTo(6_000);
        assertThat(jdbc.sql("SELECT count(*) FROM payment_transfers WHERE payment_id = :p").param("p", pay).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void onlyThePlatformCanReadTheBalanceOfItsConnectedAccounts() throws Exception {
        ledger.onConnectedAccountCreated(id("evt"), shopA, platform, false);
        splitPayment(10_000, new Transfer(shopA, 6_000));

        mvc.perform(get("/v1/balance").param("connected_account", shopA)
                        .with(jwt().jwt(j -> j.claim("mid", platform).claim("role", "owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending[0].amount").value(6_000));
        mvc.perform(get("/v1/balance_transactions").param("connected_account", shopA).param("type", "transfer")
                        .with(jwt().jwt(j -> j.claim("mid", platform).claim("role", "owner"))))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].net").value(6_000));
        mvc.perform(get("/v1/balance").param("connected_account", shopA)
                        .with(jwt().jwt(j -> j.claim("mid", "mer_other").claim("role", "owner"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/balance").param("connected_account", "acct_unknown")
                        .with(jwt().jwt(j -> j.claim("mid", platform).claim("role", "owner"))))
                .andExpect(status().isNotFound());
    }
}
