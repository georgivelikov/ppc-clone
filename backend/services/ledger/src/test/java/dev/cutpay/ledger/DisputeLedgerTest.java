package dev.cutpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cutpay.ledger.posting.LedgerEvents.DisputeClosed;
import dev.cutpay.ledger.posting.LedgerEvents.DisputeOpened;
import dev.cutpay.ledger.posting.LedgerEvents.PaymentSucceeded;
import dev.cutpay.ledger.posting.LedgerService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A dispute takes the money and a fee when it opens; winning gives both back, losing leaves the customer with the money. */
@SpringBootTest(properties = {"cutpay.outbox.poll-interval=PT0.2S", "cutpay.ledger.availability-poll-interval=PT1H"})
@Testcontainers
class DisputeLedgerTest {

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

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** The balance of one kind of account: negative when it holds money owed to someone. */
    private long account(String merchant, String type) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(p.amount), 0) FROM postings p JOIN accounts a ON a.id = p.account_id
                         WHERE a.merchant_id = :m AND a.type = :t
                        """).param("m", merchant).param("t", type).query(Long.class).single();
    }

    private long platform(String type) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(p.amount), 0) FROM postings p JOIN accounts a ON a.id = p.account_id
                         WHERE a.merchant_id IS NULL AND a.type = :t AND a.livemode = false
                        """).param("t", type).query(Long.class).single();
    }

    private long remaining(String payment) {
        return jdbc.sql("SELECT remaining FROM pending_releases WHERE payment_id = :p").param("p", payment).query(Long.class).single();
    }

    private String pay(String merchant, long amount) {
        String payment = id("pay");
        ledger.onPaymentSucceeded(new PaymentSucceeded(id("evt"), clock.instant(), merchant, false, payment, amount, "EUR",
                "consumer", "eea", List.of()));
        return payment;
    }

    private DisputeOpened opened(String merchant, String payment, String dispute, long amount, long fee) {
        return new DisputeOpened(id("evt"), clock.instant(), merchant, false, dispute, payment, amount, "EUR", fee);
    }

    private long total() {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single();
    }

    @Test
    void aDisputeOnFundsStillPendingHoldsThemAndWinningReturnsEverything() {
        String merchant = id("mer");
        String payment = pay(merchant, 10_000);
        long pending = account(merchant, "merchant_pending");
        long remainingBefore = remaining(payment);
        long feesBefore = platform("platform_fees");
        long heldBefore = platform("disputes_held");
        assertThat(pending).isNegative();

        String dispute = id("dp");
        ledger.onDisputeOpened(opened(merchant, payment, dispute, 10_000, 1_500));

        assertThat(account(merchant, "merchant_pending")).isEqualTo(pending + 10_000);
        assertThat(platform("disputes_held")).isEqualTo(heldBefore - 10_000);
        assertThat(account(merchant, "merchant_available")).isEqualTo(1_500); // the fee is owed: available is below zero
        assertThat(platform("platform_fees")).isEqualTo(feesBefore - 1_500);
        assertThat(remaining(payment)).isEqualTo(remainingBefore - 10_000);
        assertThat(total()).isZero();
        assertThat(jdbc.sql("SELECT type FROM balance_transactions WHERE merchant_id = :m AND type LIKE 'dispute%' ORDER BY type")
                .param("m", merchant).query(String.class).list()).containsExactly("dispute", "dispute_fee");

        ledger.onDisputeClosed(new DisputeClosed(id("evt"), clock.instant(), merchant, false, dispute, true));

        assertThat(account(merchant, "merchant_pending")).isEqualTo(pending);
        assertThat(account(merchant, "merchant_available")).isZero();
        assertThat(platform("disputes_held")).isEqualTo(heldBefore);
        assertThat(platform("platform_fees")).isEqualTo(feesBefore);
        assertThat(remaining(payment)).isEqualTo(remainingBefore);
        assertThat(total()).isZero();
        assertThat(jdbc.sql("SELECT type FROM balance_transactions WHERE merchant_id = :m AND type LIKE 'dispute%' ORDER BY type")
                .param("m", merchant).query(String.class).list())
                .containsExactly("dispute", "dispute_fee", "dispute_fee_reversal", "dispute_reversal");
    }

    @Test
    void aDisputeOnReleasedFundsComesOutOfAvailableAndLosingKeepsTheFee() {
        String merchant = id("mer");
        String payment = pay(merchant, 10_000);
        jdbc.sql("UPDATE pending_releases SET available_at = now() - interval '1 day' WHERE payment_id = :p").param("p", payment).update();
        assertThat(ledger.releaseDueFunds(100)).isGreaterThanOrEqualTo(1);
        long available = account(merchant, "merchant_available");
        assertThat(available).isNegative();
        long customer = platform("customer_funds");
        long heldBefore = platform("disputes_held");

        String dispute = id("dp");
        ledger.onDisputeOpened(opened(merchant, payment, dispute, 10_000, 1_500));
        assertThat(account(merchant, "merchant_available")).isEqualTo(available + 10_000 + 1_500);

        ledger.onDisputeClosed(new DisputeClosed(id("evt"), clock.instant(), merchant, false, dispute, false));

        // The customer is paid out of what was held; the merchant does not get it back and keeps paying the fee.
        assertThat(platform("disputes_held")).isEqualTo(heldBefore);
        assertThat(platform("customer_funds")).isEqualTo(customer - 10_000);
        assertThat(account(merchant, "merchant_available")).isEqualTo(available + 11_500);
        assertThat(total()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM balance_transactions WHERE merchant_id = :m AND type = 'dispute_reversal'")
                .param("m", merchant).query(Integer.class).single()).isZero();
    }

    @Test
    void theSameEventTwiceChangesNothingAndACloseNeedsItsOpen() {
        String merchant = id("mer");
        String payment = pay(merchant, 5_000);
        DisputeOpened open = opened(merchant, payment, id("dp"), 5_000, 1_500);
        ledger.onDisputeOpened(open);
        long held = platform("disputes_held");
        ledger.onDisputeOpened(open);
        assertThat(platform("disputes_held")).isEqualTo(held);

        // An unknown dispute is retried later, when its opening has arrived.
        assertThatThrownBy(() -> ledger.onDisputeClosed(new DisputeClosed(id("evt"), clock.instant(), merchant, false, "dp_unknown", true)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.onDisputeOpened(opened(merchant, "pay_unknown", id("dp"), 100, 1_500)))
                .isInstanceOf(IllegalStateException.class);
    }
}
