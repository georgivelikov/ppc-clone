package dev.cutpay.ledger.fx;

import dev.cutpay.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The exchange rates the platform trades at. Every rate is stored per one euro; the rate between two other currencies
 * is the quotient of two of them. A job moves the rates by a small random step every minute, so they look alive but stay
 * within 10% of where they started (the seed in the V5 migration, mirrored here).
 */
@Repository
public class RateBook {

    /** What one euro bought when the platform was set up. The random walk never strays far from these. */
    static final Map<String, BigDecimal> SEED = Map.of(
            "RON", new BigDecimal("4.97"),
            "PLN", new BigDecimal("4.28"),
            "HUF", new BigDecimal("395.00"),
            "CZK", new BigDecimal("24.40"),
            "GBP", new BigDecimal("0.86"),
            "USD", new BigDecimal("1.08"),
            "CHF", new BigDecimal("0.95"));

    /** A step of the walk is a normal variable with this standard deviation: 0.15% a minute. */
    private static final double STEP = 0.0015;
    private static final BigDecimal BAND = new BigDecimal("0.10");

    public record Snapshot(Map<String, BigDecimal> perEuro, Instant asOf) {

        /** What one unit of {@code from} buys of {@code to}. */
        public BigDecimal rate(String from, String to) {
            BigDecimal eurToFrom = perEuro.get(from);
            BigDecimal eurToTo = perEuro.get(to);
            if (eurToFrom == null || eurToTo == null) {
                throw ApiException.invalidRequest("unsupported_currency", "No exchange rate for " + from + " to " + to,
                        "currency");
            }
            return FxMath.cross(eurToFrom, eurToTo);
        }
    }

    private final JdbcClient jdbc;

    public RateBook(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest rate of every currency, with the time of the newest of them. */
    public Snapshot latest() {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        rates.put("EUR", BigDecimal.ONE);
        record Row(String quote, BigDecimal rate, Instant asOf) {
        }
        var rows = jdbc.sql("""
                        SELECT DISTINCT ON (quote) quote, rate, as_of FROM fx_rates
                         WHERE base = 'EUR' ORDER BY quote, as_of DESC, id DESC
                        """)
                .query((rs, n) -> new Row(rs.getString("quote").trim(), rs.getBigDecimal("rate"),
                        rs.getTimestamp("as_of").toInstant()))
                .list();
        Instant asOf = Instant.EPOCH;
        for (Row row : rows) {
            rates.put(row.quote(), row.rate());
            if (row.asOf().isAfter(asOf)) {
                asOf = row.asOf();
            }
        }
        return new Snapshot(rates, asOf);
    }

    /** Moves every rate one step and records the new values. */
    public Snapshot step(RandomGenerator random, Instant now) {
        Snapshot current = latest();
        for (var seed : SEED.entrySet()) {
            BigDecimal old = current.perEuro().get(seed.getKey());
            if (old == null) {
                old = seed.getValue();
            }
            BigDecimal moved = old.multiply(BigDecimal.valueOf(1 + random.nextGaussian() * STEP));
            BigDecimal low = seed.getValue().multiply(BigDecimal.ONE.subtract(BAND));
            BigDecimal high = seed.getValue().multiply(BigDecimal.ONE.add(BAND));
            BigDecimal next = moved.max(low).min(high).setScale(8, RoundingMode.HALF_EVEN);
            jdbc.sql("INSERT INTO fx_rates (base, quote, rate, source, as_of) VALUES ('EUR', :quote, :rate, 'random_walk', :asOf)")
                    .param("quote", seed.getKey()).param("rate", next)
                    .param("asOf", java.sql.Timestamp.from(now)).update();
        }
        return latest();
    }

    /** Old history is not needed to price anything; keep a couple of days. */
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM fx_rates WHERE as_of < :cutoff AND id NOT IN ("
                        + "SELECT DISTINCT ON (quote) id FROM fx_rates WHERE base = 'EUR' ORDER BY quote, as_of DESC, id DESC)")
                .param("cutoff", java.sql.Timestamp.from(cutoff)).update();
    }
}
