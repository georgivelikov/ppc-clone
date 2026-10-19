package dev.cutpay.ledger.fx;

import static dev.cutpay.ledger.posting.AccountType.MERCHANT_AVAILABLE;
import static dev.cutpay.ledger.posting.AccountType.PLATFORM_FEES;
import static dev.cutpay.ledger.posting.AccountType.PLATFORM_FX;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.ledger.config.Topics;
import dev.cutpay.ledger.posting.Accounts;
import dev.cutpay.ledger.posting.PostingEngine;
import dev.cutpay.ledger.posting.PostingEngine.Entry;
import dev.cutpay.ledger.posting.PostingEngine.Line;
import dev.cutpay.ledger.posting.Sql;
import dev.cutpay.money.SupportedCurrencies;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts money between a merchant's balances. A conversion moves available funds from one currency to another at a
 * rate that includes the platform's markup. It is recorded as two journal entries, one in each currency, because an
 * entry never mixes currencies; the platform's own {@code platform_fx} account of each currency is the other side:
 *
 * <pre>
 *   source currency   merchant available   debit  amount
 *                     platform fx          credit amount
 *   target currency   platform fx          debit  market value
 *                     merchant available   credit what the merchant receives
 *                     platform fees        credit the markup
 * </pre>
 *
 * Each entry balances in its own currency, so the trial balance is zero per currency. What is left on the platform's FX
 * accounts is the platform's open position: long what merchants sold it, short what it gave them.
 */
@Service
public class FxService {

    private static final Logger log = LoggerFactory.getLogger(FxService.class);

    /** How long a quoted rate is honoured. */
    public static final Duration QUOTE_VALIDITY = Duration.ofMinutes(15);

    public static final int DEFAULT_MARKUP_BPS = 100;

    public record Settings(List<String> settlementCurrencies, boolean autoConvert, int fxMarkupBps) {
    }

    public record Quote(String id, String fromCurrency, String toCurrency, long fromAmount, long midAmount, long toAmount,
                        long markupAmount, BigDecimal rate, int markupBps, Instant expiresAt, long expiresIn,
                        boolean livemode) {
    }

    public record Conversion(String id, String fromCurrency, String toCurrency, long fromAmount, long midAmount,
                             long toAmount, long markupAmount, BigDecimal rate, int markupBps, String kind,
                             String quoteId, String sourceId, Instant createdAt) {
    }

    private final RateBook rates;
    private final PostingEngine engine;
    private final Accounts accounts;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final Clock clock;

    public FxService(RateBook rates, PostingEngine engine, Accounts accounts, OutboxWriter outbox, JdbcClient jdbc,
                     Clock clock) {
        this.rates = rates;
        this.engine = engine;
        this.accounts = accounts;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // --- settings ----------------------------------------------------------------------------------

    /** A new merchant settles in its default currency and nothing is converted until it asks for that. */
    @Transactional
    public void createSettingsIfMissing(String merchantId, String defaultCurrency) {
        jdbc.sql("""
                        INSERT INTO merchant_currency_settings (merchant_id, settlement_currencies)
                        VALUES (:merchant, ARRAY[CAST(:currency AS TEXT)]) ON CONFLICT (merchant_id) DO NOTHING
                        """)
                .param("merchant", merchantId).param("currency", defaultCurrency).update();
    }

    @Transactional(readOnly = true)
    public Optional<Settings> settings(String merchantId) {
        return jdbc.sql("""
                        SELECT settlement_currencies, auto_convert, fx_markup_bps FROM merchant_currency_settings
                         WHERE merchant_id = :merchant
                        """)
                .param("merchant", merchantId)
                .query((rs, n) -> new Settings(List.of((String[]) rs.getArray("settlement_currencies").getArray()),
                        rs.getBoolean("auto_convert"), rs.getInt("fx_markup_bps")))
                .optional();
    }

    @Transactional
    public Settings updateSettings(String merchantId, List<String> currencies, boolean autoConvert) {
        List<String> cleaned = currencies.stream().map(c -> c.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if (cleaned.isEmpty()) {
            throw ApiException.invalidRequest("settlement_currency_required", "Choose at least one settlement currency",
                    "settlement_currencies");
        }
        for (String currency : cleaned) {
            if (!SupportedCurrencies.isSupported(currency)) {
                throw ApiException.invalidRequest("unsupported_currency", currency + " is not supported",
                        "settlement_currencies");
            }
        }
        jdbc.sql("""
                        INSERT INTO merchant_currency_settings (merchant_id, settlement_currencies, auto_convert)
                        VALUES (:merchant, CAST(:currencies AS TEXT[]), :auto)
                        ON CONFLICT (merchant_id) DO UPDATE
                           SET settlement_currencies = EXCLUDED.settlement_currencies,
                               auto_convert = EXCLUDED.auto_convert, updated_at = now()
                        """)
                .param("merchant", merchantId)
                .param("currencies", "{" + String.join(",", cleaned) + "}")
                .param("auto", autoConvert).update();
        return settings(merchantId).orElseThrow();
    }

    // --- quotes ------------------------------------------------------------------------------------

    @Transactional
    public Quote quote(String merchantId, boolean livemode, String from, String to, long amount) {
        String source = currency(from, "from_currency");
        String target = currency(to, "to_currency");
        requireDifferent(source, target);
        if (amount < 1) {
            throw ApiException.invalidRequest("invalid_amount", "The amount must be at least 1", "amount");
        }
        int markupBps = markupFor(merchantId);
        BigDecimal rate = rates.latest().rate(source, target);
        FxMath.Conversion math = FxMath.convert(amount, source, target, rate, markupBps);
        Instant now = clock.instant();
        Instant expires = now.plus(QUOTE_VALIDITY);
        String id = Ids.generate("fxq");
        jdbc.sql("""
                        INSERT INTO fx_quotes (id, merchant_id, livemode, from_currency, to_currency, from_amount,
                                               mid_amount, to_amount, rate, markup_bps, expires_at, created_at)
                        VALUES (:id, :merchant, :livemode, :from, :to, :fromAmount, :mid, :toAmount, :rate, :bps,
                                :expires, :now)
                        """)
                .param("id", id).param("merchant", merchantId).param("livemode", livemode).param("from", source)
                .param("to", target).param("fromAmount", amount).param("mid", math.mid()).param("toAmount", math.merchant())
                .param("rate", rate).param("bps", markupBps).param("expires", Sql.ts(expires)).param("now", Sql.ts(now))
                .update();
        return new Quote(id, source, target, amount, math.mid(), math.merchant(), math.markup(), rate, markupBps, expires,
                QUOTE_VALIDITY.toSeconds(), livemode);
    }

    // --- conversions -------------------------------------------------------------------------------

    /**
     * Converts available funds on the merchant's request, at the quoted rate when a quote is given and otherwise at
     * the current one. A quote is for exactly one conversion of exactly its amount.
     */
    @Transactional
    public Conversion convert(String merchantId, boolean livemode, String from, String to, long amount, String quoteId) {
        String source = currency(from, "from_currency");
        String target = currency(to, "to_currency");
        requireDifferent(source, target);
        if (amount < 1) {
            throw ApiException.invalidRequest("invalid_amount", "The amount must be at least 1", "amount");
        }
        BigDecimal rate;
        int markupBps;
        if (quoteId != null && !quoteId.isBlank()) {
            record Locked(String from, String to, long amount, BigDecimal rate, int bps, Instant expires, boolean used) {
            }
            Locked quote = jdbc.sql("""
                            SELECT from_currency, to_currency, from_amount, rate, markup_bps, expires_at, used_at
                              FROM fx_quotes WHERE id = :id AND merchant_id = :merchant AND livemode = :livemode FOR UPDATE
                            """)
                    .param("id", quoteId).param("merchant", merchantId).param("livemode", livemode)
                    .query((rs, n) -> new Locked(rs.getString("from_currency").trim(), rs.getString("to_currency").trim(),
                            rs.getLong("from_amount"), rs.getBigDecimal("rate"), rs.getInt("markup_bps"),
                            Sql.instant(rs, "expires_at"), rs.getObject("used_at") != null))
                    .optional()
                    .orElseThrow(() -> ApiException.notFound("quote_not_found", "No such quote"));
            if (quote.used()) {
                throw ApiException.conflict("quote_used", "This quote was already used", "quote");
            }
            if (!clock.instant().isBefore(quote.expires())) {
                throw ApiException.conflict("quote_expired", "This quote has expired, ask for a new one", "quote");
            }
            if (!quote.from().equals(source) || !quote.to().equals(target) || quote.amount() != amount) {
                throw ApiException.invalidRequest("quote_mismatch", "The quote is for another conversion", "quote");
            }
            rate = quote.rate();
            markupBps = quote.bps();
            jdbc.sql("UPDATE fx_quotes SET used_at = :now WHERE id = :id")
                    .param("now", Sql.ts(clock.instant())).param("id", quoteId).update();
        } else {
            rate = rates.latest().rate(source, target);
            markupBps = markupFor(merchantId);
        }
        FxMath.Conversion math = FxMath.convert(amount, source, target, rate, markupBps);
        if (math.merchant() < 1) {
            throw ApiException.invalidRequest("amount_too_small", "That amount is too small to convert", "amount");
        }
        requireAvailable(merchantId, livemode, source, amount);
        return post(merchantId, livemode, source, target, math, rate, markupBps, "manual",
                quoteId == null || quoteId.isBlank() ? null : quoteId, null);
    }

    /**
     * Called when funds of a payment become available. If the merchant asked for it, and the payment was in a currency
     * the merchant does not settle in, the funds are converted into the first settlement currency at the current rate.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleInto(String merchantId, boolean livemode, String currency, long amount, String sourceId) {
        if (amount < 1) {
            return;
        }
        Settings settings = settings(merchantId).orElse(null);
        if (settings == null || !settings.autoConvert() || settings.settlementCurrencies().isEmpty()
                || settings.settlementCurrencies().contains(currency)) {
            return;
        }
        String target = settings.settlementCurrencies().get(0);
        BigDecimal rate = rates.latest().rate(currency, target);
        FxMath.Conversion math = FxMath.convert(amount, currency, target, rate, settings.fxMarkupBps());
        if (math.merchant() < 1) {
            log.info("Leaving {} {} of {} unconverted: too small", amount, currency, sourceId);
            return;
        }
        post(merchantId, livemode, currency, target, math, rate, settings.fxMarkupBps(), "auto", null, sourceId);
    }

    private Conversion post(String merchantId, boolean livemode, String source, String target, FxMath.Conversion math,
                            BigDecimal rate, int markupBps, String kind, String quoteId, String sourceId) {
        Instant now = clock.instant();
        String id = Ids.generate("fxc");
        String outEntry = engine.post(new Entry("fxc_" + id + ":out", "fx_conversion",
                "Conversion %s to %s".formatted(source, target), merchantId, livemode, source, id, now, List.of(
                new Line(accounts.merchant(merchantId, livemode, MERCHANT_AVAILABLE, source), math.from()),
                new Line(accounts.platform(livemode, PLATFORM_FX, source), -math.from())))).orElseThrow();
        String inEntry = engine.post(new Entry("fxc_" + id + ":in", "fx_conversion",
                "Conversion %s to %s".formatted(source, target), merchantId, livemode, target, id, now, List.of(
                new Line(accounts.platform(livemode, PLATFORM_FX, target), math.mid()),
                new Line(accounts.merchant(merchantId, livemode, MERCHANT_AVAILABLE, target), -math.merchant()),
                new Line(accounts.platform(livemode, PLATFORM_FEES, target), -math.markup())))).orElseThrow();

        // What the merchant sees on the Balances page: the money that left and the money that arrived, with the markup
        // shown as the fee on the arriving side.
        balanceTransaction(merchantId, livemode, source, id, -math.from(), 0, -math.from(), "Converted to " + target, outEntry,
                now);
        balanceTransaction(merchantId, livemode, target, id, math.mid(), math.markup(), math.merchant(),
                "Converted from " + source, inEntry, now);

        jdbc.sql("""
                        INSERT INTO fx_conversions (id, merchant_id, livemode, from_currency, to_currency, from_amount,
                                                    mid_amount, to_amount, markup_amount, rate, markup_bps, kind, quote_id,
                                                    source_id, from_entry_id, to_entry_id, created_at)
                        VALUES (:id, :merchant, :livemode, :from, :to, :fromAmount, :mid, :toAmount, :markup, :rate, :bps,
                                :kind, :quote, :source, :outEntry, :inEntry, :now)
                        """)
                .param("id", id).param("merchant", merchantId).param("livemode", livemode).param("from", source)
                .param("to", target).param("fromAmount", math.from()).param("mid", math.mid())
                .param("toAmount", math.merchant()).param("markup", math.markup()).param("rate", rate).param("bps", markupBps)
                .param("kind", kind).param("quote", quoteId, java.sql.Types.VARCHAR)
                .param("source", sourceId, java.sql.Types.VARCHAR).param("outEntry", outEntry).param("inEntry", inEntry)
                .param("now", Sql.ts(now)).update();

        // Payouts adds up what became payable: less in the source currency, more in the target one.
        fundsAvailable(outEntry, id, merchantId, livemode, source, -math.from());
        fundsAvailable(inEntry, id, merchantId, livemode, target, math.merchant());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversion_id", id);
        payload.put("kind", kind);
        payload.put("from_currency", source);
        payload.put("from_amount", math.from());
        payload.put("to_currency", target);
        payload.put("to_amount", math.merchant());
        payload.put("mid_amount", math.mid());
        payload.put("markup_amount", math.markup());
        payload.put("rate", rate);
        payload.put("source_id", sourceId);
        outbox.write(Topics.FX, "CurrencyConverted", merchantId, merchantId, livemode, payload);
        return new Conversion(id, source, target, math.from(), math.mid(), math.merchant(), math.markup(), rate, markupBps,
                kind, quoteId, sourceId, now);
    }

    private void balanceTransaction(String merchantId, boolean livemode, String currency, String conversionId, long gross,
                                    long fee, long net, String description, String entryId, Instant now) {
        jdbc.sql("""
                        INSERT INTO balance_transactions (id, merchant_id, livemode, currency, type, source_id, parent_id,
                                                          gross, fee, net, description, journal_entry_id, available_on,
                                                          created_at)
                        VALUES (:id, :merchant, :livemode, :currency, 'conversion', :source, NULL, :gross, :fee, :net,
                                :description, :entry, :now, :now)
                        """)
                .param("id", Ids.generate("txn")).param("merchant", merchantId).param("livemode", livemode)
                .param("currency", currency).param("source", conversionId).param("gross", gross).param("fee", fee)
                .param("net", net).param("description", description).param("entry", entryId).param("now", Sql.ts(now))
                .update();
    }

    private void fundsAvailable(String entryId, String conversionId, String merchantId, boolean livemode, String currency,
                                long amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entry_id", entryId);
        payload.put("source_id", conversionId);
        payload.put("currency", currency);
        payload.put("amount", amount);
        payload.put("reason", "conversion");
        outbox.write(Topics.LEDGER_ENTRIES, "FundsAvailable", merchantId, merchantId, livemode, payload);
    }

    private void requireAvailable(String merchantId, boolean livemode, String currency, long amount) {
        String account = accounts.merchant(merchantId, livemode, MERCHANT_AVAILABLE, currency);
        // Lock the balance so two conversions cannot both spend the same money. The ledger stores what the platform owes
        // the merchant as a negative number.
        long available = -jdbc.sql("SELECT amount FROM balances WHERE account_id = :account FOR UPDATE")
                .param("account", account).query(Long.class).optional().orElse(0L);
        if (amount > available) {
            throw ApiException.conflict("insufficient_balance",
                    "Only %d is available in %s".formatted(available, currency), "amount");
        }
    }

    private int markupFor(String merchantId) {
        return settings(merchantId).map(Settings::fxMarkupBps).orElse(DEFAULT_MARKUP_BPS);
    }

    private static String currency(String code, String param) {
        if (code == null || !SupportedCurrencies.isSupported(code)) {
            throw ApiException.invalidRequest("unsupported_currency", "That currency is not supported", param);
        }
        return code.toUpperCase(Locale.ROOT);
    }

    private static void requireDifferent(String from, String to) {
        if (from.equals(to)) {
            throw ApiException.invalidRequest("same_currency", "Choose two different currencies", "to_currency");
        }
    }

    // --- reading -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Conversion> conversions(String merchantId, boolean livemode, String startingAfter, int limit) {
        List<Conversion> rows = new ArrayList<>(jdbc.sql("""
                        SELECT id, from_currency, to_currency, from_amount, mid_amount, to_amount, markup_amount, rate,
                               markup_bps, kind, quote_id, source_id, created_at
                          FROM fx_conversions
                         WHERE merchant_id = :merchant AND livemode = :livemode
                           AND (CAST(:after AS TEXT) IS NULL OR id < :after)
                         ORDER BY id DESC LIMIT :limit
                        """)
                .param("merchant", merchantId).param("livemode", livemode)
                .param("after", startingAfter, java.sql.Types.VARCHAR).param("limit", limit)
                .query((rs, n) -> new Conversion(rs.getString("id"), rs.getString("from_currency").trim(),
                        rs.getString("to_currency").trim(), rs.getLong("from_amount"), rs.getLong("mid_amount"),
                        rs.getLong("to_amount"), rs.getLong("markup_amount"), rs.getBigDecimal("rate"),
                        rs.getInt("markup_bps"), rs.getString("kind"), rs.getString("quote_id"), rs.getString("source_id"),
                        Sql.instant(rs, "created_at")))
                .list());
        return rows;
    }

    public record ReportRow(String currency, long conversions, long received, long markup) {
    }

    /** What the merchant received in each currency through conversions, and how much of it went to the markup. */
    @Transactional(readOnly = true)
    public List<ReportRow> report(String merchantId, boolean livemode, Instant since) {
        return jdbc.sql("""
                        SELECT to_currency, count(*) AS conversions, COALESCE(SUM(to_amount), 0) AS received,
                               COALESCE(SUM(markup_amount), 0) AS markup
                          FROM fx_conversions
                         WHERE merchant_id = :merchant AND livemode = :livemode AND created_at >= :since
                         GROUP BY to_currency ORDER BY to_currency
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("since", Sql.ts(since))
                .query((rs, n) -> new ReportRow(rs.getString("to_currency").trim(), rs.getLong("conversions"),
                        rs.getLong("received"), rs.getLong("markup")))
                .list();
    }

    public RateBook.Snapshot snapshot() {
        return rates.latest();
    }

    /** One step of the random walk, and a note to whoever is interested. */
    @Transactional
    public RateBook.Snapshot stepRates(java.util.random.RandomGenerator random) {
        Instant now = clock.instant();
        RateBook.Snapshot snapshot = rates.step(random, now);
        rates.purgeOlderThan(now.minus(Duration.ofDays(2)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("base", "EUR");
        payload.put("as_of", snapshot.asOf().toString());
        payload.put("rates", snapshot.perEuro());
        outbox.write(Topics.FX, "RatesUpdated", "EUR", null, null, payload);
        return snapshot;
    }
}
