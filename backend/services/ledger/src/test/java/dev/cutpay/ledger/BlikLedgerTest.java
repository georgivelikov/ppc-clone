package dev.cutpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cutpay.ledger.fees.PricingPlan;
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

/** BLIK has its own price, 1.29% + PLN 0.25, instead of the card schedule. Wallet payments are priced as cards. */
@SpringBootTest(properties = {"cutpay.outbox.poll-interval=PT0.2S", "cutpay.ledger.availability-poll-interval=PT1H"})
@Testcontainers
class BlikLedgerTest {

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

    @Test
    void theFeeIsOnePointTwoNinePercentPlusTwentyFiveGroszy() {
        assertThat(PricingPlan.blikFee(10_000)).isEqualTo(154);
        assertThat(PricingPlan.blikFee(12_345)).isEqualTo(184); // 159.25 -> 159, + 25
        // Never more than the payment itself.
        assertThat(PricingPlan.blikFee(20)).isEqualTo(20);
    }

    @Test
    void aBlikPaymentIsPostedWithTheBlikFeeAndBalances() {
        String merchant = id("mer");
        String pay = id("pay");
        ledger.onPaymentSucceeded(new PaymentSucceeded(id("evt"), clock.instant(), merchant, false, pay, 10_000, "PLN",
                null, null, List.of(), null, "blik"));

        var row = jdbc.sql("SELECT gross, fee, net FROM balance_transactions WHERE merchant_id = :m AND type = 'payment'")
                .param("m", merchant).query((rs, n) -> List.of(rs.getLong("gross"), rs.getLong("fee"), rs.getLong("net")))
                .single();
        assertThat(row).containsExactly(10_000L, 154L, 9_846L);
        assertThat(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single()).isZero();
    }

    @Test
    void aCardPaymentOfTheSameSizeStillPaysTheCardFee() {
        String merchant = id("mer");
        ledger.onPaymentSucceeded(new PaymentSucceeded(id("evt"), clock.instant(), merchant, false, id("pay"), 10_000,
                "PLN", "consumer", "eea", List.of(), null, "google_pay"));

        long fee = jdbc.sql("SELECT fee FROM balance_transactions WHERE merchant_id = :m AND type = 'payment'")
                .param("m", merchant).query(Long.class).single();
        assertThat(fee).isEqualTo(139); // 1.29% + 0.10
    }
}
