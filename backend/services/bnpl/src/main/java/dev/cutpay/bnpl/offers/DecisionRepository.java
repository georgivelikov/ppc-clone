package dev.cutpay.bnpl.offers;

import static dev.cutpay.bnpl.config.Sql.instant;
import static dev.cutpay.bnpl.config.Sql.ts;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the record of why a provider was chosen. */
@Repository
public class DecisionRepository {

    public record Stored(String id, String paymentId, String strategy, long amount, String currency, String country,
                         String chosenProvider, String reason, String detailJson, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    DecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Stored d, String merchantId, boolean livemode) {
        jdbc.sql("""
                        INSERT INTO routing_decisions (id, payment_id, merchant_id, livemode, strategy, amount, currency, country,
                                                       chosen_provider, reason, detail, created_at)
                        VALUES (:id, :payment, :merchant, :livemode, :strategy, :amount, :currency, :country, :chosen, :reason,
                                CAST(:detail AS jsonb), :now)
                        """)
                .param("id", d.id()).param("payment", d.paymentId()).param("merchant", merchantId).param("livemode", livemode)
                .param("strategy", d.strategy()).param("amount", d.amount()).param("currency", d.currency())
                .param("country", d.country()).param("chosen", d.chosenProvider(), java.sql.Types.VARCHAR)
                .param("reason", d.reason()).param("detail", d.detailJson()).param("now", ts(d.createdAt())).update();
    }

    /** The most recent decision for a payment of the merchant: offers can be asked for again, and the last one counts. */
    public Optional<Stored> latest(String merchantId, boolean livemode, String paymentId) {
        return jdbc.sql("""
                        SELECT id, payment_id, strategy, amount, currency, country, chosen_provider, reason, detail::text AS detail,
                               created_at
                          FROM routing_decisions
                         WHERE merchant_id = :merchant AND livemode = :livemode AND payment_id = :payment
                         ORDER BY created_at DESC, id DESC LIMIT 1
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("payment", paymentId)
                .query((rs, n) -> new Stored(rs.getString("id"), rs.getString("payment_id"), rs.getString("strategy"),
                        rs.getLong("amount"), rs.getString("currency").trim(), rs.getString("country"),
                        rs.getString("chosen_provider"), rs.getString("reason"), rs.getString("detail"),
                        instant(rs, "created_at")))
                .optional();
    }
}
