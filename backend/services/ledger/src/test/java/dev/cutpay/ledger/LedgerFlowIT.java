package dev.cutpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cutpay.ledger.fees.CardProfile;
import dev.cutpay.ledger.fees.PricingPlan;
import dev.cutpay.ledger.posting.AccountType;
import dev.cutpay.ledger.posting.LedgerEvents.PaymentSucceeded;
import dev.cutpay.ledger.posting.LedgerEvents.Payout;
import dev.cutpay.ledger.posting.LedgerEvents.RefundSucceeded;
import dev.cutpay.ledger.posting.LedgerService;
import dev.cutpay.web.SimulatedClock;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ledger against a real Postgres and Kafka. Events are fed to {@link LedgerService} directly (that is
 * what the Kafka listener does after parsing), plus one test that goes through a real topic.
 *
 * <p>Payments are timestamped with the simulated clock, so a test that moves time forward cannot make
 * another test's payments due early.
 */
@SpringBootTest(properties = {
        "cutpay.outbox.poll-interval=PT0.2S",
        // The tests call the availability logic themselves; the background job would only add noise.
        "cutpay.ledger.availability-poll-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class LedgerFlowIT {

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

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    private String merchant;

    @BeforeEach
    void freshMerchant() {
        merchant = "mer_" + UUID.randomUUID().toString().replace("-", "");
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private PaymentSucceeded payment(String paymentId, long amount, String type, String region) {
        return new PaymentSucceeded(id("evt"), clock.instant(), merchant, false, paymentId, amount, "EUR", type, region);
    }

    private RefundSucceeded refund(String paymentId, long amount) {
        return new RefundSucceeded(id("evt"), clock.instant(), merchant, false, id("re"), paymentId, amount, "EUR");
    }

    private Payout payout(String payoutId, long amount, long fee) {
        return new Payout(id("evt"), clock.instant(), merchant, false, payoutId, amount, fee, "EUR");
    }

    private void advanceDays(long days) {
        ((SimulatedClock) clock).advance(Duration.ofDays(days));
    }

    private void releaseAll() {
        int released;
        do {
            released = ledger.releaseDueFunds(100);
        } while (released == 100);
    }

    /** What the platform owes this merchant in the given account (balances are stored as credits, so negated). */
    private long owed(String merchantId, AccountType type) {
        return -jdbc.sql("""
                        SELECT COALESCE(SUM(b.amount), 0) FROM accounts a JOIN balances b ON b.account_id = a.id
                         WHERE a.merchant_id = :merchant AND a.type = :type AND a.livemode = false
                        """)
                .param("merchant", merchantId)
                .param("type", type.wire())
                .query(Long.class).single();
    }

    private long pending() {
        return owed(merchant, AccountType.MERCHANT_PENDING);
    }

    private long available() {
        return owed(merchant, AccountType.MERCHANT_AVAILABLE);
    }

    private long feesCharged() {
        return jdbc.sql("SELECT COALESCE(SUM(fee), 0) FROM balance_transactions "
                        + "WHERE merchant_id = :merchant AND type = 'payment'")
                .param("merchant", merchant).query(Long.class).single();
    }

    private List<Long> fundsAvailableAmounts(String merchantId) {
        return jdbc.sql("SELECT payload FROM outbox WHERE topic = 'ledger.entries' "
                        + "AND event_type = 'FundsAvailable' AND event_key = :merchant ORDER BY id")
                .param("merchant", merchantId)
                .query(String.class).list().stream()
                .map(payload -> json.readTree(payload).path("payload").path("amount").asLong())
                .toList();
    }

    private void assertTrialBalanceIsZero() {
        assertThat(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single())
                .as("trial balance").isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM accounts a LEFT JOIN balances b ON b.account_id = a.id
                         WHERE COALESCE(b.amount, 0) <>
                               (SELECT COALESCE(SUM(p.amount), 0) FROM postings p WHERE p.account_id = a.id)
                        """).query(Long.class).single())
                .as("accounts whose materialised balance disagrees with their postings").isZero();
    }

    private static void eventually(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            Thread.sleep(100);
        }
    }

    private RequestPostProcessor asMerchant() {
        return jwt().jwt(j -> j.claim("mid", merchant).claim("role", "owner"));
    }

    // --- pricing and balances ---------------------------------------------------------------------

    @Test
    void aHundredEuroConsumerPaymentNetsNinetyEightSixtyOneAndLandsInPending() {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));

        assertThat(pending()).isEqualTo(9_861);
        assertThat(available()).isZero();
        assertThat(feesCharged()).isEqualTo(139);
        assertTrialBalanceIsZero();
    }

    @Test
    void businessAndNonEeaCardsPayTheHigherFee() {
        ledger.onPaymentSucceeded(payment("pay_b", 10_000, "business", "eea"));
        ledger.onPaymentSucceeded(payment("pay_n", 10_000, "consumer", "non_eea"));

        assertThat(feesCharged()).isEqualTo(279 + 279);
        assertThat(pending()).isEqualTo(2 * (10_000 - 279));
    }

    @Test
    void aPaymentWithoutCardDetailsIsPricedAsAnEeaConsumerCard() {
        ledger.onPaymentSucceeded(payment("pay_old", 10_000, null, null));

        assertThat(feesCharged()).isEqualTo(139);
    }

    @Test
    void aTinyPaymentNeverNetsLessThanZero() {
        ledger.onPaymentSucceeded(payment("pay_tiny", 5, "consumer", "eea"));

        assertThat(feesCharged()).isEqualTo(5);
        assertThat(pending()).isZero();
        assertTrialBalanceIsZero();
    }

    // --- availability -----------------------------------------------------------------------------

    @Test
    void fundsBecomeAvailableOnlyAfterTheSettlementDelay() {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));

        advanceDays(1);
        releaseAll();
        assertThat(pending()).isEqualTo(9_861);
        assertThat(available()).isZero();

        advanceDays(1);
        releaseAll();
        assertThat(pending()).isZero();
        assertThat(available()).isEqualTo(9_861);
        assertThat(fundsAvailableAmounts(merchant)).containsExactly(9_861L);
        assertTrialBalanceIsZero();
    }

    @Test
    void releasingTwiceDoesNotMoveTheFundsTwice() {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));
        advanceDays(2);

        releaseAll();
        releaseAll();

        assertThat(available()).isEqualTo(9_861);
        assertThat(fundsAvailableAmounts(merchant)).hasSize(1);
    }

    // --- refunds ----------------------------------------------------------------------------------

    @Test
    void aRefundBeforeReleaseComesOutOfPendingAndShrinksWhatIsReleased() {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));
        ledger.onRefundSucceeded(refund("pay_1", 3_000));

        // The fee is not returned: 98.61 - 30.00 = 68.61 is what the merchant keeps.
        assertThat(pending()).isEqualTo(6_861);

        advanceDays(2);
        releaseAll();
        assertThat(available()).isEqualTo(6_861);
        assertThat(fundsAvailableAmounts(merchant)).containsExactly(6_861L);
        assertTrialBalanceIsZero();
    }

    @Test
    void aRefundAfterReleaseMakesAvailableNegativeAndIsDeductedFromTheNextPayout() {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));
        advanceDays(2);
        releaseAll();
        ledger.onPayoutCreated(payout("po_1", 9_861, 0)); // everything was paid out

        ledger.onRefundSucceeded(refund("pay_1", 3_000));

        assertThat(available()).isEqualTo(-3_000);
        assertThat(fundsAvailableAmounts(merchant)).containsExactly(9_861L, -3_000L);
        assertTrialBalanceIsZero();
    }

    @Test
    void aFullRefundBeforeReleaseLeavesTheMerchantOutOfPocketByTheFee() {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));
        ledger.onRefundSucceeded(refund("pay_1", 10_000));

        assertThat(pending()).isEqualTo(-139);

        advanceDays(2);
        releaseAll();
        assertThat(pending()).isZero();
        assertThat(available()).isEqualTo(-139);
        assertTrialBalanceIsZero();
    }

    @Test
    void aRefundForAPaymentTheLedgerHasNotSeenYetFailsSoItIsRetried() {
        assertThatThrownBy(() -> ledger.onRefundSucceeded(refund("pay_unknown", 1_000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pay_unknown");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM processed_events pe JOIN journal_entries j ON j.event_id = pe.event_id "
                + "WHERE j.merchant_id = :merchant").param("merchant", merchant).query(Long.class).single()).isZero();
    }

    // --- payouts ----------------------------------------------------------------------------------

    @Test
    void aPayoutMovesFundsOutOfAvailableAndIsPaidOut() {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));
        advanceDays(2);
        releaseAll();

        Payout created = payout("po_1", 9_861, 0);
        ledger.onPayoutCreated(created);
        assertThat(available()).isZero();

        ledger.onPayoutPaid(payout("po_1", 9_861, 0));
        assertTrialBalanceIsZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM journal_entries WHERE merchant_id = :merchant "
                + "AND entry_type = 'payout_paid'").param("merchant", merchant).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void aFailedPayoutReturnsTheFundsAndAnyInstantFee() {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));
        advanceDays(2);
        releaseAll();
        // An instant payout of everything: 98.61 gross, 0.99 fee, 97.62 reaches the bank.
        ledger.onPayoutCreated(payout("po_1", 9_762, 99));
        assertThat(available()).isZero();

        ledger.onPayoutFailed(payout("po_1", 9_762, 99));

        assertThat(available()).isEqualTo(9_861);
        assertThat(fundsAvailableAmounts(merchant)).containsExactly(9_861L, 9_861L);
        assertTrialBalanceIsZero();
    }

    @Test
    void scheduleChangesApplyToPaymentsMadeAfterwards() {
        ledger.onMerchantCreated(id("evt"), merchant, "EUR");
        ledger.onPayoutScheduleChanged(id("evt"), merchant, 5);

        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));
        advanceDays(2);
        releaseAll();
        assertThat(pending()).isEqualTo(9_861);

        advanceDays(3);
        releaseAll();
        assertThat(available()).isEqualTo(9_861);
    }

    // --- idempotency ------------------------------------------------------------------------------

    @Test
    void redeliveredEventsChangeNothing() {
        PaymentSucceeded payment = payment("pay_1", 10_000, "consumer", "eea");
        RefundSucceeded refund = refund("pay_1", 1_000);
        ledger.onPaymentSucceeded(payment);
        ledger.onRefundSucceeded(refund);
        long before = pending();

        ledger.onPaymentSucceeded(payment);
        ledger.onRefundSucceeded(refund);
        ledger.onMerchantCreated("evt_same", merchant, "EUR");
        ledger.onMerchantCreated("evt_same", merchant, "EUR");

        assertThat(pending()).isEqualTo(before).isEqualTo(8_861);
        assertThat(feesCharged()).isEqualTo(139);
        assertTrialBalanceIsZero();
    }

    @Test
    void aPaymentDeliveredTwiceThroughKafkaIsLedgeredOnce() throws Exception {
        String first = envelope("PaymentSucceeded", "pay_k1", 10_000);
        // Same partition key for all three, so Kafka delivers them in this order.
        kafka.send("payment.events", "k", first).get();
        kafka.send("payment.events", "k", first).get();
        kafka.send("payment.events", "k", envelope("PaymentSucceeded", "pay_k2", 10_000)).get();

        eventually(Duration.ofSeconds(30), () -> pending() == 2 * 9_861);

        // Had the duplicate been posted, pending would be 3 x 9,861 by now.
        assertThat(pending()).isEqualTo(2 * 9_861);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM journal_entries WHERE merchant_id = :merchant")
                .param("merchant", merchant).query(Long.class).single()).isEqualTo(2L);
        assertTrialBalanceIsZero();
    }

    private String envelope(String type, String paymentId, long amount) {
        return """
                {"event_id":"%s","event_type":"%s","occurred_at":"%s","aggregate_id":"%s","merchant_id":"%s",
                 "livemode":false,"version":1,
                 "payload":{"payment_id":"%s","status":"succeeded","amount":%d,"currency":"EUR",
                            "card_type":"consumer","card_region":"eea"}}"""
                .formatted(type.equals("PaymentSucceeded") && paymentId.equals("pay_k1") ? "evt_fixed_" + merchant
                                : id("evt"),
                        type, clock.instant(), paymentId, merchant, paymentId, amount);
    }

    // --- API --------------------------------------------------------------------------------------

    @Test
    void theBalanceEndpointShowsWhatTheMerchantIsOwedPerCurrency() throws Exception {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));

        mvc.perform(get("/v1/balance").with(asMerchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.livemode").value(false))
                .andExpect(jsonPath("$.pending[0].amount").value(9_861))
                .andExpect(jsonPath("$.pending[0].currency").value("EUR"))
                .andExpect(jsonPath("$.available[0].amount").value(0));
    }

    @Test
    void balanceTransactionsCarryGrossFeeAndNetAndCanBeFilteredByPayment() throws Exception {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));
        ledger.onPaymentSucceeded(payment("pay_2", 5_000, "consumer", "eea"));
        ledger.onRefundSucceeded(refund("pay_1", 1_000));

        mvc.perform(get("/v1/balance_transactions").param("source", "pay_1").with(asMerchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2)) // the payment and the refund against it
                .andExpect(jsonPath("$.data[?(@.type=='payment')].gross").value(10_000))
                .andExpect(jsonPath("$.data[?(@.type=='payment')].fee").value(139))
                .andExpect(jsonPath("$.data[?(@.type=='payment')].net").value(9_861))
                .andExpect(jsonPath("$.data[?(@.type=='refund')].parent_id").value("pay_1"));
    }

    @Test
    void aMerchantNeverSeesAnotherMerchantsMoney() throws Exception {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));
        String other = "mer_" + UUID.randomUUID().toString().replace("-", "");

        mvc.perform(get("/v1/balance_transactions")
                        .with(jwt().jwt(j -> j.claim("mid", other).claim("role", "owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void balanceEndpointsRequireAToken() throws Exception {
        mvc.perform(get("/v1/balance")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/balance_transactions")).andExpect(status().isUnauthorized());
    }

    @Test
    void theTrialBalanceEndpointReportsABalancedLedger() throws Exception {
        ledger.onPaymentSucceeded(payment("pay_1", 10_000, "consumer", "eea"));
        ledger.onRefundSucceeded(refund("pay_1", 2_500));

        mvc.perform(get("/admin/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.balance_drift").value(0));
    }

    // --- property test ----------------------------------------------------------------------------

    /**
     * Random sequences of payments, refunds, time passing and payouts. After every single step the whole
     * ledger must still sum to zero, every materialised balance must match its postings, and what the
     * merchant is owed must equal what an independent running total says. At the end, replaying every
     * event must change nothing.
     */
    @Test
    void randomEventSequencesKeepEveryInvariant() {
        for (long seed = 1; seed <= 15; seed++) {
            runRandomSequence(seed);
        }
    }

    private void runRandomSequence(long seed) {
        Random random = new Random(seed);
        merchant = "mer_prop_" + seed + "_" + UUID.randomUUID().toString().replace("-", "");
        PricingPlan plan = PricingPlan.standard(merchant);

        Map<String, Long> refundable = new HashMap<>();
        List<Runnable> replay = new ArrayList<>();
        long expectedOwed = 0;
        int payments = 0;

        for (int step = 0; step < 40; step++) {
            String context = "seed " + seed + ", step " + step;
            switch (random.nextInt(5)) {
                case 0, 1 -> {
                    long amount = 1 + random.nextInt(200_000);
                    String[] type = {"consumer", "business", null};
                    String[] region = {"eea", "non_eea", null};
                    String cardType = type[random.nextInt(type.length)];
                    String cardRegion = region[random.nextInt(region.length)];
                    PaymentSucceeded event = payment("pay_" + seed + "_" + (payments++), amount, cardType, cardRegion);
                    long fee = plan.strategy().feeFor(amount, CardProfile.of(cardType, cardRegion));
                    ledger.onPaymentSucceeded(event);
                    replay.add(() -> ledger.onPaymentSucceeded(event));
                    refundable.put(event.paymentId(), amount);
                    expectedOwed += amount - fee;
                }
                case 2 -> {
                    List<String> candidates = refundable.entrySet().stream()
                            .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).sorted().toList();
                    if (!candidates.isEmpty()) {
                        String paymentId = candidates.get(random.nextInt(candidates.size()));
                        long amount = 1 + random.nextInt(refundable.get(paymentId).intValue());
                        RefundSucceeded event = refund(paymentId, amount);
                        ledger.onRefundSucceeded(event);
                        replay.add(() -> ledger.onRefundSucceeded(event));
                        refundable.merge(paymentId, -amount, Long::sum);
                        expectedOwed -= amount;
                    }
                }
                case 3 -> {
                    advanceDays(random.nextInt(4));
                    releaseAll();
                }
                default -> {
                    long payable = available();
                    if (payable > 1) {
                        long gross = 1 + random.nextInt((int) Math.min(payable, Integer.MAX_VALUE));
                        long fee = random.nextBoolean() ? gross / 100 : 0;
                        if (gross - fee > 0) {
                            String payoutId = "po_" + seed + "_" + step;
                            Payout created = payout(payoutId, gross - fee, fee);
                            ledger.onPayoutCreated(created);
                            replay.add(() -> ledger.onPayoutCreated(created));
                            expectedOwed -= gross;
                            switch (random.nextInt(3)) {
                                case 0 -> {
                                    Payout paid = payout(payoutId, gross - fee, fee);
                                    ledger.onPayoutPaid(paid);
                                    replay.add(() -> ledger.onPayoutPaid(paid));
                                }
                                case 1 -> {
                                    Payout failed = payout(payoutId, gross - fee, fee);
                                    ledger.onPayoutFailed(failed);
                                    replay.add(() -> ledger.onPayoutFailed(failed));
                                    expectedOwed += gross;
                                }
                                default -> { } // still in transit
                            }
                        }
                    }
                }
            }
            assertThat(pending() + available()).as("owed to merchant, " + context).isEqualTo(expectedOwed);
            assertTrialBalanceIsZero();
        }

        replay.forEach(Runnable::run);
        assertThat(pending() + available()).as("owed after replaying everything, seed " + seed)
                .isEqualTo(expectedOwed);
        assertTrialBalanceIsZero();
    }
}
