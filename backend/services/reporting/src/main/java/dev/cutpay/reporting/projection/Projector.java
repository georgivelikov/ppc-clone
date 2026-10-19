package dev.cutpay.reporting.projection;

import dev.cutpay.events.InboxGuard;
import dev.cutpay.reporting.config.Topics;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Turns events into rows of the read model. It is the only code that writes the {@code fact_*} tables, and it is used both
 * by the live listeners and by the rebuild, so a replayed history gives the numbers the live stream did.
 *
 * <p>Every upsert is safe in any order: a row remembers the time of the last event that changed it, an older event does not
 * overwrite a newer one, and dates that only ever go one way (opened, first attempt, success) keep the earliest. Seeing the
 * same event twice changes nothing, with or without the inbox.
 */
@Component
public class Projector {

    private static final Set<String> NOT_AN_ATTEMPT = Set.of("PaymentIntentCreated", "PaymentCanceled");

    private final JdbcClient jdbc;
    private final InboxGuard inbox;

    public Projector(JdbcClient jdbc, InboxGuard inbox) {
        this.jdbc = jdbc;
        this.inbox = inbox;
    }

    /** @return true when the event changed anything (it was new and of a kind the read model uses) */
    @Transactional
    public boolean apply(String topic, JsonNode event) {
        String type = text(event, "event_type");
        if (!inbox.firstTime(text(event, "event_id"))) {
            return false;
        }
        JsonNode payload = event.path("payload");
        Instant at = Instant.parse(text(event, "occurred_at"));
        switch (topic) {
            case Topics.PAYMENT -> payment(type, event, payload, at);
            case Topics.PAYOUT -> payout(type, event, payload, at);
            case Topics.DISPUTE -> dispute(event, payload, at);
            case Topics.LEDGER_ENTRIES -> {
                if (!"BalanceTransactionRecorded".equals(type)) {
                    return false;
                }
                balanceTransaction(event, payload);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void payment(String type, JsonNode event, JsonNode p, Instant at) {
        if (!type.startsWith("Payment") || p.path("payment_id").isMissingNode()) {
            return;
        }
        Timestamp now = Timestamp.from(at);
        boolean attempt = !NOT_AN_ATTEMPT.contains(type);
        jdbc.sql("""
                        INSERT INTO fact_payments (payment_id, merchant_id, livemode, currency, amount, status, method_type, card_brand,
                                                   failure_code, channel, created_at, attempted_at, succeeded_at, last_event_at)
                        VALUES (:id, :merchant, :live, :currency, :amount, :status, :method, :brand, :failure, :channel, :at,
                                :attempted, :succeeded, :at)
                        ON CONFLICT (payment_id) DO UPDATE SET
                          created_at = LEAST(fact_payments.created_at, EXCLUDED.created_at),
                          attempted_at = CASE WHEN EXCLUDED.attempted_at IS NULL THEN fact_payments.attempted_at
                                              WHEN fact_payments.attempted_at IS NULL THEN EXCLUDED.attempted_at
                                              ELSE LEAST(fact_payments.attempted_at, EXCLUDED.attempted_at) END,
                          succeeded_at = CASE WHEN EXCLUDED.succeeded_at IS NULL THEN fact_payments.succeeded_at
                                              WHEN fact_payments.succeeded_at IS NULL THEN EXCLUDED.succeeded_at
                                              ELSE LEAST(fact_payments.succeeded_at, EXCLUDED.succeeded_at) END,
                          status = CASE WHEN EXCLUDED.last_event_at >= fact_payments.last_event_at THEN EXCLUDED.status ELSE fact_payments.status END,
                          method_type = CASE WHEN EXCLUDED.last_event_at >= fact_payments.last_event_at
                                             THEN COALESCE(EXCLUDED.method_type, fact_payments.method_type) ELSE fact_payments.method_type END,
                          card_brand = CASE WHEN EXCLUDED.last_event_at >= fact_payments.last_event_at
                                            THEN COALESCE(EXCLUDED.card_brand, fact_payments.card_brand) ELSE fact_payments.card_brand END,
                          failure_code = CASE WHEN EXCLUDED.last_event_at >= fact_payments.last_event_at THEN EXCLUDED.failure_code
                                              ELSE fact_payments.failure_code END,
                          last_event_at = GREATEST(fact_payments.last_event_at, EXCLUDED.last_event_at)
                        """)
                .param("id", text(p, "payment_id")).param("merchant", text(event, "merchant_id"))
                .param("live", event.path("livemode").asBoolean(false)).param("currency", text(p, "currency"))
                .param("amount", p.path("amount").asLong()).param("status", text(p, "status"))
                .param("method", nullable(p, "payment_method_type")).param("brand", nullable(p, "card_brand"))
                .param("failure", nullable(p, "failure_code")).param("channel", nullable(p, "channel")).param("at", now)
                .param("attempted", attempt ? now : null, java.sql.Types.TIMESTAMP)
                .param("succeeded", "PaymentSucceeded".equals(type) ? now : null, java.sql.Types.TIMESTAMP)
                .update();
    }

    private void payout(String type, JsonNode event, JsonNode p, Instant at) {
        if (!type.startsWith("Payout") || p.path("payout_id").isMissingNode()) {
            return;
        }
        Timestamp now = Timestamp.from(at);
        jdbc.sql("""
                        INSERT INTO fact_payouts (payout_id, merchant_id, livemode, currency, amount, fee, status, kind, arrival_date,
                                                  created_at, last_event_at)
                        VALUES (:id, :merchant, :live, :currency, :amount, :fee, :status, :kind, :arrival, :at, :at)
                        ON CONFLICT (payout_id) DO UPDATE SET
                          created_at = LEAST(fact_payouts.created_at, EXCLUDED.created_at),
                          status = CASE WHEN EXCLUDED.last_event_at >= fact_payouts.last_event_at THEN EXCLUDED.status ELSE fact_payouts.status END,
                          arrival_date = CASE WHEN EXCLUDED.last_event_at >= fact_payouts.last_event_at
                                              THEN COALESCE(EXCLUDED.arrival_date, fact_payouts.arrival_date) ELSE fact_payouts.arrival_date END,
                          last_event_at = GREATEST(fact_payouts.last_event_at, EXCLUDED.last_event_at)
                        """)
                .param("id", text(p, "payout_id")).param("merchant", text(event, "merchant_id"))
                .param("live", event.path("livemode").asBoolean(false)).param("currency", text(p, "currency"))
                .param("amount", p.path("amount").asLong()).param("fee", p.path("fee").asLong())
                .param("status", text(p, "status")).param("kind", nullable(p, "kind"))
                .param("arrival", nullable(p, "arrival_date")).param("at", now).update();
    }

    private void dispute(JsonNode event, JsonNode p, Instant at) {
        if (p.path("dispute_id").isMissingNode()) {
            return;
        }
        Timestamp now = Timestamp.from(at);
        String status = text(p, "status");
        boolean closed = status.equals("won") || status.equals("lost");
        jdbc.sql("""
                        INSERT INTO fact_disputes (dispute_id, merchant_id, livemode, payment_id, currency, amount, fee, status, reason,
                                                   created_at, closed_at, last_event_at)
                        VALUES (:id, :merchant, :live, :payment, :currency, :amount, :fee, :status, :reason, :at, :closed, :at)
                        ON CONFLICT (dispute_id) DO UPDATE SET
                          created_at = LEAST(fact_disputes.created_at, EXCLUDED.created_at),
                          closed_at = CASE WHEN EXCLUDED.closed_at IS NULL THEN fact_disputes.closed_at
                                           WHEN fact_disputes.closed_at IS NULL THEN EXCLUDED.closed_at
                                           ELSE LEAST(fact_disputes.closed_at, EXCLUDED.closed_at) END,
                          status = CASE WHEN EXCLUDED.last_event_at >= fact_disputes.last_event_at THEN EXCLUDED.status ELSE fact_disputes.status END,
                          last_event_at = GREATEST(fact_disputes.last_event_at, EXCLUDED.last_event_at)
                        """)
                .param("id", text(p, "dispute_id")).param("merchant", text(event, "merchant_id"))
                .param("live", event.path("livemode").asBoolean(false)).param("payment", text(p, "payment_id"))
                .param("currency", text(p, "currency")).param("amount", p.path("amount").asLong())
                .param("fee", p.path("fee").asLong()).param("status", status).param("reason", nullable(p, "reason"))
                .param("at", now).param("closed", closed ? now : null, java.sql.Types.TIMESTAMP).update();
    }

    private void balanceTransaction(JsonNode event, JsonNode p) {
        jdbc.sql("""
                        INSERT INTO fact_balance_transactions (transaction_id, merchant_id, livemode, currency, type, source_id, parent_id,
                                                               gross, fee, net, description, created_at)
                        VALUES (:id, :merchant, :live, :currency, :type, :source, :parent, :gross, :fee, :net, :description, :at)
                        ON CONFLICT (transaction_id) DO NOTHING
                        """)
                .param("id", text(p, "transaction_id")).param("merchant", text(event, "merchant_id"))
                .param("live", event.path("livemode").asBoolean(false)).param("currency", text(p, "currency"))
                .param("type", text(p, "type")).param("source", nullable(p, "source_id")).param("parent", nullable(p, "parent_id"))
                .param("gross", p.path("gross").asLong()).param("fee", p.path("fee").asLong()).param("net", p.path("net").asLong())
                .param("description", nullable(p, "description")).param("at", Timestamp.from(Instant.parse(text(p, "created_at"))))
                .update();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Event is missing required field " + field);
        }
        return value.asString();
    }

    private static String nullable(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
