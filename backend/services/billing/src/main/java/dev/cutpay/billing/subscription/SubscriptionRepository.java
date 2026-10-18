package dev.cutpay.billing.subscription;

import static dev.cutpay.billing.config.Sql.instant;
import static dev.cutpay.billing.config.Sql.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL for subscriptions. Shared by the subscription service and the invoice service, which moves them along. */
@Repository
public class SubscriptionRepository {

    public static final String TRIALING = "trialing";
    public static final String ACTIVE = "active";
    public static final String PAST_DUE = "past_due";
    public static final String CANCELED = "canceled";
    public static final String UNPAID = "unpaid";
    public static final String INCOMPLETE = "incomplete";

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, merchant_id, merchant_name, livemode, customer_id, customer_email, customer_name, status, currency,
            billing_interval, interval_count, collection_method, default_payment_method, billing_cycle_anchor,
            period_index, current_period_start, current_period_end, trial_end, cancel_at_period_end,
            collection_paused, canceled_at, ended_at, tax_rate_bps, latest_invoice_id, metadata::text AS metadata_json,
            created_at, updated_at
            """;

    public record Item(String id, String priceId, int quantity) {
    }

    public record Subscription(String id, String merchantId, String merchantName, boolean livemode, String customerId,
                               String customerEmail, String customerName, String status, String currency,
                               Interval interval, int intervalCount, String collectionMethod,
                               String defaultPaymentMethod, Instant billingCycleAnchor, int periodIndex,
                               Instant currentPeriodStart, Instant currentPeriodEnd, Instant trialEnd,
                               boolean cancelAtPeriodEnd, boolean collectionPaused, Instant canceledAt,
                               Instant endedAt, int taxRateBps, String latestInvoiceId, Map<String, String> metadata,
                               Instant createdAt, Instant updatedAt, List<Item> items) {
        public boolean ended() {
            return CANCELED.equals(status);
        }
    }

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    SubscriptionRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(Subscription s) {
        jdbc.sql("""
                        INSERT INTO subscriptions (id, merchant_id, merchant_name, livemode, customer_id, customer_email,
                            customer_name, status, currency, billing_interval, interval_count, collection_method,
                            default_payment_method, billing_cycle_anchor, period_index, current_period_start,
                            current_period_end, trial_end, cancel_at_period_end, collection_paused, tax_rate_bps,
                            metadata, created_at, updated_at)
                        VALUES (:id, :merchant, :merchantName, :livemode, :customer, :email, :name, :status, :currency,
                            :interval, :count, :method, :pm, :anchor, :index, :start, :end, :trialEnd, FALSE, FALSE,
                            :tax, CAST(:metadata AS jsonb), :now, :now)
                        """)
                .param("id", s.id()).param("merchant", s.merchantId()).param("merchantName", s.merchantName())
                .param("livemode", s.livemode()).param("customer", s.customerId()).param("email", s.customerEmail())
                .param("name", s.customerName()).param("status", s.status()).param("currency", s.currency())
                .param("interval", s.interval().wire()).param("count", s.intervalCount())
                .param("method", s.collectionMethod()).param("pm", s.defaultPaymentMethod())
                .param("anchor", ts(s.billingCycleAnchor())).param("index", s.periodIndex())
                .param("start", ts(s.currentPeriodStart())).param("end", ts(s.currentPeriodEnd()))
                .param("trialEnd", ts(s.trialEnd()), Types.TIMESTAMP_WITH_TIMEZONE).param("tax", s.taxRateBps())
                .param("metadata", mapper.writeValueAsString(s.metadata())).param("now", ts(s.createdAt()))
                .update();
        replaceItems(s.id(), s.items());
    }

    public void replaceItems(String subscriptionId, List<Item> items) {
        jdbc.sql("DELETE FROM subscription_items WHERE subscription_id = :id").param("id", subscriptionId).update();
        for (Item item : items) {
            jdbc.sql("""
                            INSERT INTO subscription_items (id, subscription_id, price_id, quantity)
                            VALUES (:id, :subscription, :price, :quantity)
                            """)
                    .param("id", item.id()).param("subscription", subscriptionId).param("price", item.priceId())
                    .param("quantity", item.quantity()).update();
        }
    }

    public Optional<Subscription> find(String id) {
        return one("SELECT " + COLUMNS + " FROM subscriptions WHERE id = :id", id);
    }

    /** Locks the row for the rest of the transaction, so two workers never bill the same subscription twice. */
    public Optional<Subscription> lock(String id) {
        return one("SELECT " + COLUMNS + " FROM subscriptions WHERE id = :id FOR UPDATE", id);
    }

    private Optional<Subscription> one(String sql, String id) {
        return jdbc.sql(sql).param("id", id).query(this::map).optional().map(s -> withItems(s));
    }

    public List<Subscription> page(String merchantId, boolean livemode, String status, String customerId,
                                   String startingAfter, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM subscriptions
                        WHERE merchant_id = :merchant AND livemode = :livemode
                          AND (CAST(:status AS text) IS NULL OR status = :status)
                          AND (CAST(:customer AS text) IS NULL OR customer_id = :customer)
                          AND (CAST(:after AS text) IS NULL OR id < :after)
                        ORDER BY id DESC LIMIT :limit
                        """)
                .param("merchant", merchantId).param("livemode", livemode)
                .param("status", status, Types.VARCHAR).param("customer", customerId, Types.VARCHAR)
                .param("after", startingAfter, Types.VARCHAR).param("limit", limit)
                .query(this::map).list().stream().map(this::withItems).toList();
    }

    /** Subscriptions whose period is over and that are billed again (or end) now. Oldest first. */
    public List<String> dueForBilling(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT id FROM subscriptions
                         WHERE status IN ('active', 'trialing') AND NOT collection_paused AND current_period_end <= :now
                         ORDER BY current_period_end, id LIMIT :limit
                        """)
                .param("now", ts(now)).param("limit", limit).query(String.class).list();
    }

    /** Subscriptions whose first invoice was never paid. */
    public List<String> incompleteSince(Instant cutoff, int limit) {
        return jdbc.sql("SELECT id FROM subscriptions WHERE status = 'incomplete' AND created_at <= :cutoff ORDER BY id LIMIT :limit")
                .param("cutoff", ts(cutoff)).param("limit", limit).query(String.class).list();
    }

    public void setStatus(String id, String status, Instant now) {
        jdbc.sql("UPDATE subscriptions SET status = :status, updated_at = :now WHERE id = :id")
                .param("id", id).param("status", status).param("now", ts(now)).update();
    }

    /** Ends the subscription: canceled by the merchant, at the period end, for not paying, or never completed. */
    public void end(String id, Instant now) {
        jdbc.sql("""
                        UPDATE subscriptions SET status = 'canceled', canceled_at = COALESCE(canceled_at, :now),
                               ended_at = :now, cancel_at_period_end = FALSE, updated_at = :now
                         WHERE id = :id
                        """)
                .param("id", id).param("now", ts(now)).update();
    }

    public void advancePeriod(String id, int periodIndex, Instant start, Instant end, String latestInvoiceId, Instant now) {
        jdbc.sql("""
                        UPDATE subscriptions SET period_index = :index, current_period_start = :start,
                               current_period_end = :end, trial_end = NULL, latest_invoice_id = :invoice, updated_at = :now
                         WHERE id = :id
                        """)
                .param("id", id).param("index", periodIndex).param("start", ts(start)).param("end", ts(end))
                .param("invoice", latestInvoiceId).param("now", ts(now)).update();
    }

    public void setLatestInvoice(String id, String invoiceId, Instant now) {
        jdbc.sql("UPDATE subscriptions SET latest_invoice_id = :invoice, updated_at = :now WHERE id = :id")
                .param("id", id).param("invoice", invoiceId).param("now", ts(now)).update();
    }

    /** Starts the cycle again from {@code anchor}, for a subscription that is resumed after a pause. */
    public void restartCycle(String id, Instant anchor, Instant end, Instant now) {
        jdbc.sql("""
                        UPDATE subscriptions SET billing_cycle_anchor = :anchor, period_index = 0,
                               current_period_start = :anchor, current_period_end = :end, collection_paused = FALSE,
                               updated_at = :now
                         WHERE id = :id
                        """)
                .param("id", id).param("anchor", ts(anchor)).param("end", ts(end)).param("now", ts(now)).update();
    }

    public void setFlags(String id, boolean cancelAtPeriodEnd, boolean collectionPaused, Instant now) {
        jdbc.sql("""
                        UPDATE subscriptions SET cancel_at_period_end = :cancel, collection_paused = :paused, updated_at = :now
                         WHERE id = :id
                        """)
                .param("id", id).param("cancel", cancelAtPeriodEnd).param("paused", collectionPaused)
                .param("now", ts(now)).update();
    }

    public void setDefaultPaymentMethod(String id, String paymentMethod, Instant now) {
        jdbc.sql("UPDATE subscriptions SET default_payment_method = :pm, updated_at = :now WHERE id = :id")
                .param("id", id).param("pm", paymentMethod, Types.VARCHAR).param("now", ts(now)).update();
    }

    // --- figures for the dashboard -----------------------------------------------------------------

    public record StatusCount(String status, long count) {
    }

    public List<StatusCount> countByStatus(String merchantId, boolean livemode) {
        return jdbc.sql("""
                        SELECT status, count(*) AS n FROM subscriptions
                         WHERE merchant_id = :merchant AND livemode = :livemode GROUP BY status
                        """)
                .param("merchant", merchantId).param("livemode", livemode)
                .query((rs, n) -> new StatusCount(rs.getString("status"), rs.getLong("n"))).list();
    }

    public long endedSince(String merchantId, boolean livemode, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM subscriptions
                         WHERE merchant_id = :merchant AND livemode = :livemode AND ended_at >= :since
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("since", ts(since))
                .query(Long.class).single();
    }

    /** The subscriptions that existed when a month began, and how many of them ended during it. */
    public record Cohort(long atStart, long ended) {
    }

    public Cohort cohort(String merchantId, boolean livemode, Instant monthStart, Instant monthEnd) {
        return jdbc.sql("""
                        SELECT count(*) AS at_start,
                               count(*) FILTER (WHERE ended_at IS NOT NULL AND ended_at < :end) AS ended
                          FROM subscriptions
                         WHERE merchant_id = :merchant AND livemode = :livemode AND created_at < :start
                           AND (ended_at IS NULL OR ended_at >= :start)
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("start", ts(monthStart))
                .param("end", ts(monthEnd))
                .query((rs, n) -> new Cohort(rs.getLong("at_start"), rs.getLong("ended"))).single();
    }

    /** What the paying subscriptions are worth, one row per subscription item. */
    public record Recurring(String currency, long unitAmount, int quantity, Interval interval, int intervalCount) {
    }

    public List<Recurring> recurringRevenue(String merchantId, boolean livemode) {
        return jdbc.sql("""
                        SELECT s.currency, p.unit_amount, i.quantity, s.billing_interval, s.interval_count
                          FROM subscriptions s
                          JOIN subscription_items i ON i.subscription_id = s.id
                          JOIN prices p ON p.id = i.price_id
                         WHERE s.merchant_id = :merchant AND s.livemode = :livemode
                           AND s.status IN ('active', 'past_due')
                        """)
                .param("merchant", merchantId).param("livemode", livemode)
                .query((rs, n) -> new Recurring(rs.getString("currency").trim(), rs.getLong("unit_amount"),
                        rs.getInt("quantity"), Interval.fromWire(rs.getString("billing_interval")),
                        rs.getInt("interval_count")))
                .list();
    }

    // --- mapping -----------------------------------------------------------------------------------

    private Subscription withItems(Subscription s) {
        List<Item> items = jdbc.sql("SELECT id, price_id, quantity FROM subscription_items WHERE subscription_id = :id ORDER BY id")
                .param("id", s.id())
                .query((rs, n) -> new Item(rs.getString("id"), rs.getString("price_id"), rs.getInt("quantity"))).list();
        return new Subscription(s.id(), s.merchantId(), s.merchantName(), s.livemode(), s.customerId(), s.customerEmail(),
                s.customerName(), s.status(), s.currency(), s.interval(), s.intervalCount(), s.collectionMethod(),
                s.defaultPaymentMethod(), s.billingCycleAnchor(), s.periodIndex(), s.currentPeriodStart(),
                s.currentPeriodEnd(), s.trialEnd(), s.cancelAtPeriodEnd(), s.collectionPaused(), s.canceledAt(),
                s.endedAt(), s.taxRateBps(), s.latestInvoiceId(), s.metadata(), s.createdAt(), s.updatedAt(), items);
    }

    private Subscription map(ResultSet rs, int row) throws SQLException {
        return new Subscription(rs.getString("id"), rs.getString("merchant_id"), rs.getString("merchant_name"),
                rs.getBoolean("livemode"), rs.getString("customer_id"), rs.getString("customer_email"),
                rs.getString("customer_name"), rs.getString("status"), rs.getString("currency").trim(),
                Interval.fromWire(rs.getString("billing_interval")), rs.getInt("interval_count"),
                rs.getString("collection_method"), rs.getString("default_payment_method"),
                instant(rs, "billing_cycle_anchor"), rs.getInt("period_index"), instant(rs, "current_period_start"),
                instant(rs, "current_period_end"), instant(rs, "trial_end"), rs.getBoolean("cancel_at_period_end"),
                rs.getBoolean("collection_paused"), instant(rs, "canceled_at"), instant(rs, "ended_at"),
                rs.getInt("tax_rate_bps"), rs.getString("latest_invoice_id"),
                mapper.readValue(rs.getString("metadata_json"), STRING_MAP), instant(rs, "created_at"),
                instant(rs, "updated_at"), List.of());
    }
}
