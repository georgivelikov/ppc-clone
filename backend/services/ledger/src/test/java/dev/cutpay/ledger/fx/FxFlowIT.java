package dev.cutpay.ledger.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cutpay.ledger.posting.AccountType;
import dev.cutpay.ledger.posting.LedgerEvents.PaymentSucceeded;
import dev.cutpay.ledger.posting.LedgerService;
import dev.cutpay.web.SimulatedClock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Conversions between a merchant's balances, against a real Postgres: what is posted, what is quoted, what is refused. */
@SpringBootTest(properties = {
        "cutpay.outbox.poll-interval=PT0.2S",
        "cutpay.ledger.availability-poll-interval=PT1H",
        "cutpay.ledger.fx-rate-interval=PT1H"})
@AutoConfigureMockMvc
@Testcontainers
class FxFlowIT {

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
    MockMvc mvc;

    @Autowired
    LedgerService ledger;

    @Autowired
    FxService fx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    @Autowired
    JsonMapper json;

    private String merchant;

    @BeforeEach
    void freshMerchant() {
        merchant = "mer_" + UUID.randomUUID().toString().replace("-", "");
        ledger.onMerchantCreated("evt_" + UUID.randomUUID(), merchant, "EUR");
    }

    private RequestPostProcessor asMerchant() {
        return jwt().jwt(j -> j.claim("mid", merchant).claim("role", "owner"));
    }

    /** A RON payment, paid out of pending once three days have gone by. Returns the payment id. */
    private String ronPayment(long amount) {
        String pay = "pay_" + UUID.randomUUID().toString().replace("-", "");
        ledger.onPaymentSucceeded(new PaymentSucceeded("evt_" + UUID.randomUUID(), clock.instant(), merchant, false, pay,
                amount, "RON", "consumer", "eea"));
        return pay;
    }

    private void release() {
        ((SimulatedClock) clock).advance(Duration.ofDays(3));
        while (ledger.releaseDueFunds(100) == 100) {
            // keep going until the backlog is gone
        }
    }

    private long owed(AccountType type, String currency) {
        return -jdbc.sql("""
                        SELECT COALESCE(SUM(b.amount), 0) FROM accounts a JOIN balances b ON b.account_id = a.id
                         WHERE a.merchant_id = :merchant AND a.type = :type AND a.currency = :currency AND a.livemode = false
                        """)
                .param("merchant", merchant).param("type", type.wire()).param("currency", currency)
                .query(Long.class).single();
    }

    private long platform(AccountType type, String currency) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(b.amount), 0) FROM accounts a JOIN balances b ON b.account_id = a.id
                         WHERE a.merchant_id IS NULL AND a.type = :type AND a.currency = :currency AND a.livemode = false
                        """)
                .param("type", type.wire()).param("currency", currency).query(Long.class).single();
    }

    private void assertTrialBalanceIsZeroPerCurrency() {
        var totals = jdbc.sql("SELECT currency, COALESCE(SUM(amount), 0) AS total FROM postings GROUP BY currency")
                .query((rs, n) -> rs.getString("currency").trim() + "=" + rs.getLong("total")).list();
        assertThat(totals).isNotEmpty();
        assertThat(totals).allMatch(total -> total.endsWith("=0"), totals.toString());
    }

    private ResultActions convert(String from, String to, long amount, String quote) throws Exception {
        String body = quote == null
                ? "{\"from_currency\":\"%s\",\"to_currency\":\"%s\",\"amount\":%d}".formatted(from, to, amount)
                : "{\"from_currency\":\"%s\",\"to_currency\":\"%s\",\"amount\":%d,\"quote_id\":\"%s\"}".formatted(from, to, amount, quote);
        return mvc.perform(post("/v1/balance/convert").with(asMerchant()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String quote(String from, String to, long amount) throws Exception {
        String body = mvc.perform(post("/v1/fx/quotes").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from_currency\":\"%s\",\"to_currency\":\"%s\",\"amount\":%d}".formatted(from, to, amount)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    @Test
    void aMerchantPaidInRonConvertsToEurosWithTheMarkupAndEveryCurrencyStillBalances() throws Exception {
        ronPayment(50_000); // RON 500.00
        release();
        long net = owed(AccountType.MERCHANT_AVAILABLE, "RON");
        assertThat(net).isEqualTo(49_345); // 1.29% + 0.10 comes off

        FxMath.Conversion expected = FxMath.convert(net, "RON", "EUR", fx.snapshot().rate("RON", "EUR"), 100);
        convert("RON", "EUR", net, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.object").value("fx_conversion"))
                .andExpect(jsonPath("$.from_amount").value(net))
                .andExpect(jsonPath("$.to_amount").value(expected.merchant()))
                .andExpect(jsonPath("$.mid_amount").value(expected.mid()))
                .andExpect(jsonPath("$.markup_amount").value(expected.markup()))
                .andExpect(jsonPath("$.markup_bps").value(100))
                .andExpect(jsonPath("$.kind").value("manual"));

        assertThat(owed(AccountType.MERCHANT_AVAILABLE, "RON")).isZero();
        assertThat(owed(AccountType.MERCHANT_AVAILABLE, "EUR")).isEqualTo(expected.merchant());
        // The platform took the other side: it holds the RON and has given the euros.
        assertThat(platform(AccountType.PLATFORM_FX, "RON")).isEqualTo(-net);
        assertThat(platform(AccountType.PLATFORM_FX, "EUR")).isEqualTo(expected.mid());
        assertTrialBalanceIsZeroPerCurrency();

        // The Balances page shows both sides, and the markup as the fee of the euro side.
        var rows = jdbc.sql("SELECT currency, gross, fee, net FROM balance_transactions WHERE merchant_id = :m AND type = 'conversion' ORDER BY currency")
                .param("m", merchant).query((rs, n) -> List.of(rs.getString("currency").trim(), rs.getLong("gross"), rs.getLong("fee"), rs.getLong("net")))
                .list();
        assertThat(rows).containsExactly(
                List.of("EUR", expected.mid(), expected.markup(), expected.merchant()),
                List.of("RON", -net, 0L, -net));
        // Payouts hears that less is payable in RON and more in euros.
        var events = jdbc.sql("SELECT payload FROM outbox WHERE event_key = :m AND event_type = 'FundsAvailable' ORDER BY id")
                .param("m", merchant).query(String.class).list().stream()
                .map(p -> json.readTree(p).path("payload")).filter(p -> "conversion".equals(p.path("reason").asString())).toList();
        assertThat(events).extracting(p -> p.path("currency").asString() + ":" + p.path("amount").asLong())
                .containsExactlyInAnyOrder("RON:" + -net, "EUR:" + expected.merchant());
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE topic = 'fx.events' AND event_type = 'CurrencyConverted' AND event_key = :m")
                .param("m", merchant).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void onlyAvailableMoneyCanBeConvertedAndOnlyOnce() throws Exception {
        ronPayment(50_000);
        // Still pending: nothing to convert yet.
        convert("RON", "EUR", 1_000, null).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("insufficient_balance"));
        release();
        convert("RON", "EUR", 49_346, null).andExpect(status().isConflict());
        convert("RON", "EUR", 30_000, null).andExpect(status().isCreated());
        convert("RON", "EUR", 30_000, null).andExpect(status().isConflict());
        convert("RON", "EUR", 19_345, null).andExpect(status().isCreated());
        assertThat(owed(AccountType.MERCHANT_AVAILABLE, "RON")).isZero();
        assertTrialBalanceIsZeroPerCurrency();
    }

    @Test
    void nonsenseIsRefused() throws Exception {
        convert("RON", "RON", 100, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("same_currency"));
        convert("RON", "XXX", 100, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("unsupported_currency"));
        convert("RON", "EUR", 0, null).andExpect(status().isBadRequest());
    }

    @Test
    void aQuoteHoldsItsRateWhileTheMarketMovesAndIsUsedOnce() throws Exception {
        ronPayment(50_000);
        release();
        String quote = quote("RON", "EUR", 40_000);
        BigDecimal locked = new BigDecimal(json.readTree(mvc.perform(get("/v1/fx/rates").with(asMerchant()).param("base", "RON"))
                .andReturn().getResponse().getContentAsString()).get("rates").get("EUR").asString());

        // The market moves a lot.
        for (int i = 0; i < 40; i++) {
            fx.stepRates(new Random(i));
        }
        BigDecimal now = fx.snapshot().rate("RON", "EUR");

        FxMath.Conversion atQuote = FxMath.convert(40_000, "RON", "EUR", locked, 100);
        convert("RON", "EUR", 40_000, quote).andExpect(status().isCreated())
                .andExpect(jsonPath("$.to_amount").value(atQuote.merchant()))
                .andExpect(jsonPath("$.quote_id").value(quote));
        assertThat(now).isNotEqualByComparingTo(locked);
        convert("RON", "EUR", 40_000, quote).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("quote_used"));
    }

    @Test
    void aQuoteExpiresAfterFifteenMinutesAndBelongsToOneMerchant() throws Exception {
        ronPayment(50_000);
        release();
        String quote = quote("RON", "EUR", 10_000);
        mvc.perform(post("/v1/balance/convert").with(jwt().jwt(j -> j.claim("mid", "mer_someone_else")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from_currency\":\"RON\",\"to_currency\":\"EUR\",\"amount\":10000,\"quote_id\":\"%s\"}".formatted(quote)))
                .andExpect(status().isNotFound());

        // A quote is for exactly one conversion: another amount or another pair is refused.
        convert("RON", "EUR", 9_999, quote).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("quote_mismatch"));
        convert("RON", "PLN", 10_000, quote).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("quote_mismatch"));

        ((SimulatedClock) clock).advance(Duration.ofMinutes(16));
        convert("RON", "EUR", 10_000, quote).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("quote_expired"));
        assertThat(owed(AccountType.MERCHANT_AVAILABLE, "RON")).isEqualTo(49_345);
    }

    @Test
    void withAutoConvertOnFundsAreConvertedAsTheyBecomeAvailable() throws Exception {
        mvc.perform(put("/v1/currency_settings").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlement_currencies\":[\"EUR\"],\"auto_convert\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.auto_convert").value(true))
                .andExpect(jsonPath("$.settlement_currencies[0]").value("EUR")).andExpect(jsonPath("$.fx_markup_bps").value(100));
        String pay = ronPayment(50_000);
        // Pending money stays in the currency it was paid in until it is available.
        assertThat(owed(AccountType.MERCHANT_PENDING, "RON")).isEqualTo(49_345);

        release();
        assertThat(owed(AccountType.MERCHANT_AVAILABLE, "RON")).isZero();
        assertThat(owed(AccountType.MERCHANT_AVAILABLE, "EUR")).isPositive();
        mvc.perform(get("/v1/fx/conversions").with(asMerchant()))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].kind").value("auto"))
                .andExpect(jsonPath("$.data[0].source_id").value(pay)).andExpect(jsonPath("$.data[0].from_amount").value(49_345));
        mvc.perform(get("/v1/fx/report").with(asMerchant())).andExpect(jsonPath("$.data[0].currency").value("EUR"))
                .andExpect(jsonPath("$.data[0].conversions").value(1)).andExpect(jsonPath("$.data[0].markup").isNumber());
        assertTrialBalanceIsZeroPerCurrency();
    }

    @Test
    void byDefaultNothingIsConvertedAndMoneyStaysInTheCurrencyItArrivedIn() throws Exception {
        mvc.perform(get("/v1/currency_settings").with(asMerchant())).andExpect(jsonPath("$.auto_convert").value(false))
                .andExpect(jsonPath("$.settlement_currencies[0]").value("EUR"));
        ronPayment(50_000);
        release();
        assertThat(owed(AccountType.MERCHANT_AVAILABLE, "RON")).isEqualTo(49_345);
        assertThat(owed(AccountType.MERCHANT_AVAILABLE, "EUR")).isZero();
    }

    @Test
    void aPaymentInTheSettlementCurrencyIsLeftAlone() throws Exception {
        mvc.perform(put("/v1/currency_settings").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"settlement_currencies\":[\"RON\",\"EUR\"],\"auto_convert\":true}")).andExpect(status().isOk());
        ronPayment(50_000);
        release();
        assertThat(owed(AccountType.MERCHANT_AVAILABLE, "RON")).isEqualTo(49_345);
        mvc.perform(get("/v1/fx/conversions").with(asMerchant())).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void settingsAreChecked() throws Exception {
        mvc.perform(put("/v1/currency_settings").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"settlement_currencies\":[\"XXX\"]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/v1/currency_settings").with(asMerchant()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"settlement_currencies\":[]}")).andExpect(status().isBadRequest());
    }

    @Test
    void theRatesCanBeReadFromAnyBaseAndTheWalkStaysWithinTenPercentOfTheSeed() throws Exception {
        mvc.perform(get("/v1/fx/rates").with(asMerchant())).andExpect(jsonPath("$.base").value("EUR"))
                .andExpect(jsonPath("$.rates.PLN").isNumber()).andExpect(jsonPath("$.rates.length()").value(7));
        mvc.perform(get("/v1/fx/rates").with(asMerchant()).param("base", "PLN")).andExpect(jsonPath("$.rates.EUR").isNumber())
                .andExpect(jsonPath("$.rates.PLN").doesNotExist());

        Random random = new Random(42);
        for (int i = 0; i < 300; i++) {
            fx.stepRates(random);
        }
        var snapshot = fx.snapshot();
        for (var seed : RateBook.SEED.entrySet()) {
            BigDecimal rate = snapshot.perEuro().get(seed.getKey());
            assertThat(rate).as(seed.getKey()).isBetween(seed.getValue().multiply(new BigDecimal("0.9")),
                    seed.getValue().multiply(new BigDecimal("1.1")));
        }
        // Old history is trimmed, the newest rates are kept.
        assertThat(jdbc.sql("SELECT count(DISTINCT quote) FROM fx_rates").query(Long.class).single()).isEqualTo(7);
    }
}
