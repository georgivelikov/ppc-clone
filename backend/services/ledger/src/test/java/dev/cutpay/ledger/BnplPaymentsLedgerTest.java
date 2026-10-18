package dev.cutpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.cutpay.ledger.posting.AccountType;
import dev.cutpay.ledger.posting.LedgerEvents.PaymentSucceeded;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A payment financed by a buy-now-pay-later provider: the merchant is credited the price less the provider's fee at once
 * (they are paid up front), and the provider owes the same amount to the platform until it settles.
 */
@SpringBootTest(properties = {"cutpay.outbox.poll-interval=PT0.2S", "cutpay.ledger.availability-poll-interval=PT1H"})
@Testcontainers
class BnplPaymentsLedgerTest {

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
    KafkaTemplate<String, String> kafka;

    private String merchant;
    private String pay;

    @BeforeEach
    void freshMerchant() {
        merchant = id("mer");
        pay = id("pay");
    }

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private PaymentSucceeded bnplPayment(String eventId, long amount, long fee) {
        return new PaymentSucceeded(eventId, clock.instant(), merchant, false, pay, amount, "EUR", null, null, List.of(), fee);
    }

    private long owed(String merchantId, AccountType type) {
        return -jdbc.sql("""
                        SELECT COALESCE(SUM(b.amount), 0) FROM accounts a JOIN balances b ON b.account_id = a.id
                         WHERE a.merchant_id = :merchant AND a.type = :type AND a.livemode = false
                        """)
                .param("merchant", merchantId).param("type", type.wire()).query(Long.class).single();
    }

    /** The platform-wide accounts hold the money of every test in this class, so tests look at how they change. */
    private long platform(AccountType type) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(b.amount), 0) FROM accounts a JOIN balances b ON b.account_id = a.id
                         WHERE a.merchant_id IS NULL AND a.type = :type AND a.livemode = false
                        """)
                .param("type", type.wire()).query(Long.class).single();
    }

    private void assertTrialBalanceIsZero() {
        assertThat(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single()).as("trial balance").isZero();
    }

    @Test
    void theMerchantIsCreditedThePriceLessTheProvidersFeeAndTheProviderOwesTheSame() {
        long receivable = platform(AccountType.BNPL_RECEIVABLE);
        long fees = platform(AccountType.PLATFORM_FEES);
        long customerFunds = platform(AccountType.CUSTOMER_FUNDS);

        ledger.onPaymentSucceeded(bnplPayment(id("evt"), 30_000, 1_050));

        assertThat(owed(merchant, AccountType.MERCHANT_PENDING)).isEqualTo(28_950);
        assertThat(platform(AccountType.BNPL_RECEIVABLE) - receivable).isEqualTo(28_950);
        // The provider's fee is the merchant's cost, not revenue of the platform, and no card money came in.
        assertThat(platform(AccountType.PLATFORM_FEES) - fees).isZero();
        assertThat(platform(AccountType.CUSTOMER_FUNDS) - customerFunds).isZero();
        assertTrialBalanceIsZero();
    }

    @Test
    void theMerchantSeesTheGrossTheProvidersFeeAndTheNet() {
        ledger.onPaymentSucceeded(bnplPayment(id("evt"), 30_000, 1_050));

        var row = jdbc.sql("SELECT gross, fee, net FROM balance_transactions WHERE merchant_id = :m AND type = 'payment'")
                .param("m", merchant).query((rs, n) -> List.of(rs.getLong("gross"), rs.getLong("fee"), rs.getLong("net"))).single();
        assertThat(row).containsExactly(30_000L, 1_050L, 28_950L);
    }

    @Test
    void theMerchantIsPaidUpFrontLikeAnyOtherPaymentOnceTheDelayHasPassed() {
        ledger.onPaymentSucceeded(bnplPayment(id("evt"), 30_000, 1_050));
        assertThat(owed(merchant, AccountType.MERCHANT_AVAILABLE)).isZero();

        ((SimulatedClock) clock).advance(Duration.ofDays(3));
        ledger.releaseDueFunds(100);

        assertThat(owed(merchant, AccountType.MERCHANT_PENDING)).isZero();
        assertThat(owed(merchant, AccountType.MERCHANT_AVAILABLE)).isEqualTo(28_950);
        assertTrialBalanceIsZero();
    }

    @Test
    void theSameEventIsPostedOnce() {
        String event = id("evt");

        ledger.onPaymentSucceeded(bnplPayment(event, 30_000, 1_050));
        ledger.onPaymentSucceeded(bnplPayment(event, 30_000, 1_050));

        assertThat(owed(merchant, AccountType.MERCHANT_PENDING)).isEqualTo(28_950);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM balance_transactions WHERE merchant_id = :m").param("m", merchant)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aFeeLargerThanThePriceOrASplitIsRefusedBeforeAnythingIsPosted() {
        assertThatThrownBy(() -> ledger.onPaymentSucceeded(bnplPayment(id("evt"), 30_000, 30_001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.onPaymentSucceeded(bnplPayment(id("evt"), 30_000, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.onPaymentSucceeded(new PaymentSucceeded(id("evt"), clock.instant(), merchant, false, pay,
                30_000, "EUR", null, null, List.of(new Transfer(id("acct"), 1_000)), 1_050L)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(owed(merchant, AccountType.MERCHANT_PENDING)).isZero();
    }

    @Test
    void aCardPaymentStillPaysTheCardFeeToThePlatform() {
        long fees = platform(AccountType.PLATFORM_FEES);

        ledger.onPaymentSucceeded(new PaymentSucceeded(id("evt"), clock.instant(), merchant, false, pay, 10_000, "EUR",
                "consumer", "eea"));

        assertThat(owed(merchant, AccountType.MERCHANT_PENDING)).isEqualTo(9_861);
        assertThat(-(platform(AccountType.PLATFORM_FEES) - fees)).isEqualTo(139);
    }

    @Test
    void thePaymentEventFromKafkaIsReadForTheMethodAndTheFee() {
        kafka.send("payment.events", pay, """
                {"event_id":"%s","event_type":"PaymentSucceeded","occurred_at":"2026-10-19T10:00:00Z","aggregate_id":"%s",
                 "merchant_id":"%s","livemode":false,"version":1,
                 "payload":{"payment_id":"%s","status":"succeeded","amount":30000,"currency":"EUR","payment_method_type":"bnpl",
                            "bnpl_provider":"splitnow","bnpl_application_id":"bnpl_1","bnpl_fee":1050}}"""
                .formatted(id("evt"), pay, merchant, pay));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(owed(merchant, AccountType.MERCHANT_PENDING)).isEqualTo(28_950));
        assertTrialBalanceIsZero();
    }
}
