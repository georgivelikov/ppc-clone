package dev.cutpay.bnpl.application;

import static dev.cutpay.bnpl.config.Sql.instant;
import static dev.cutpay.bnpl.config.Sql.ts;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL for applications. The offer a customer chose is copied in whole and never changes afterwards. */
@Repository
public class ApplicationRepository {

    public record Application(String id, String paymentId, String merchantId, boolean livemode, String provider,
                              String plan, ApplicationStatus status, String providerRef, long amount, String currency,
                              String country, int instalmentCount, List<Long> instalments, int intervalDays,
                              double aprPercent, long totalPayable, long merchantFee, String customerEmail,
                              String declineReason, Long decisionMs, Instant expiresAt, Instant createdAt,
                              Instant decidedAt, Instant settledAt, Instant updatedAt) {
    }

    private static final String COLUMNS = """
            id, payment_id, merchant_id, livemode, provider, plan, status, provider_ref, amount, currency, country,
            instalment_count, instalments::text AS instalments, interval_days, apr_percent, total_payable, merchant_fee,
            customer_email, decline_reason, decision_ms, expires_at, created_at, decided_at, settled_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ApplicationRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Application a) {
        jdbc.sql("""
                        INSERT INTO bnpl_applications (id, payment_id, merchant_id, livemode, provider, plan, status, amount,
                            currency, country, instalment_count, instalments, interval_days, apr_percent, total_payable,
                            merchant_fee, customer_email, expires_at, created_at, updated_at)
                        VALUES (:id, :payment, :merchant, :livemode, :provider, :plan, :status, :amount, :currency, :country,
                            :count, CAST(:instalments AS jsonb), :interval, :apr, :total, :fee, :email, :expires, :now, :now)
                        """)
                .param("id", a.id()).param("payment", a.paymentId()).param("merchant", a.merchantId())
                .param("livemode", a.livemode()).param("provider", a.provider()).param("plan", a.plan())
                .param("status", a.status().wire()).param("amount", a.amount()).param("currency", a.currency())
                .param("country", a.country()).param("count", a.instalmentCount())
                .param("instalments", json.writeValueAsString(a.instalments())).param("interval", a.intervalDays())
                .param("apr", a.aprPercent()).param("total", a.totalPayable()).param("fee", a.merchantFee())
                .param("email", a.customerEmail(), Types.VARCHAR).param("expires", ts(a.expiresAt()))
                .param("now", ts(a.createdAt())).update();
    }

    public Optional<Application> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bnpl_applications WHERE id = :id").param("id", id)
                .query(this::map).optional();
    }

    public Optional<Application> lock(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bnpl_applications WHERE id = :id FOR UPDATE").param("id", id)
                .query(this::map).optional();
    }

    /** The application of a payment that is still alive, if any: at most one may be at a time. */
    public Optional<Application> blockingFor(String paymentId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM bnpl_applications
                        WHERE payment_id = :payment AND status IN ('created', 'pending_review', 'approved', 'settled')
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .param("payment", paymentId).query(this::map).optional();
    }

    public List<Application> page(String merchantId, boolean livemode, String status, String provider, String paymentId,
                                  String startingAfter, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM bnpl_applications
                        WHERE merchant_id = :merchant AND livemode = :livemode
                          AND (CAST(:status AS text) IS NULL OR status = :status)
                          AND (CAST(:provider AS text) IS NULL OR provider = :provider)
                          AND (CAST(:payment AS text) IS NULL OR payment_id = :payment)
                          AND (CAST(:after AS text) IS NULL OR id < :after)
                        ORDER BY id DESC LIMIT :limit
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("status", status, Types.VARCHAR)
                .param("provider", provider, Types.VARCHAR).param("payment", paymentId, Types.VARCHAR)
                .param("after", startingAfter, Types.VARCHAR).param("limit", limit).query(this::map).list();
    }

    /** Applications waiting for a decision that the provider will not push to us, oldest first. */
    public List<Application> waiting(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bnpl_applications WHERE status = 'pending_review' ORDER BY created_at LIMIT :limit")
                .param("limit", limit).query(this::map).list();
    }

    public List<Application> expired(Instant now, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM bnpl_applications WHERE status IN ('created', 'pending_review') AND expires_at <= :now
                        ORDER BY expires_at LIMIT :limit
                        """)
                .param("now", ts(now)).param("limit", limit).query(this::map).list();
    }

    public void update(String id, ApplicationStatus status, String providerRef, String declineReason, Long decisionMs,
                       Instant decidedAt, Instant now) {
        jdbc.sql("""
                        UPDATE bnpl_applications SET status = :status, provider_ref = COALESCE(:ref, provider_ref),
                               decline_reason = COALESCE(:reason, decline_reason), decision_ms = COALESCE(:ms, decision_ms),
                               decided_at = COALESCE(:decided, decided_at), updated_at = :now
                         WHERE id = :id
                        """)
                .param("id", id).param("status", status.wire()).param("ref", providerRef, Types.VARCHAR)
                .param("reason", declineReason, Types.VARCHAR).param("ms", decisionMs, Types.BIGINT)
                .param("decided", ts(decidedAt), Types.TIMESTAMP_WITH_TIMEZONE).param("now", ts(now)).update();
    }

    /** How one provider has done for a merchant over a period. Rates are left to the caller. */
    public record ProviderFigures(String provider, long applications, long approved, long declined, long failed, long pending,
                                  long abandoned, double avgDecisionMs, double p50DecisionMs, long volume, long fees) {
    }

    public List<ProviderFigures> figures(String merchantId, boolean livemode, Instant since) {
        return jdbc.sql("""
                        SELECT provider,
                               count(*) AS applications,
                               count(*) FILTER (WHERE status IN ('approved', 'settled')) AS approved,
                               count(*) FILTER (WHERE status = 'declined') AS declined,
                               count(*) FILTER (WHERE status = 'failed') AS failed,
                               count(*) FILTER (WHERE status IN ('created', 'pending_review')) AS pending,
                               count(*) FILTER (WHERE status IN ('expired', 'canceled')) AS abandoned,
                               COALESCE(avg(decision_ms), 0) AS avg_ms,
                               COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY decision_ms), 0) AS p50_ms,
                               COALESCE(sum(amount) FILTER (WHERE status IN ('approved', 'settled')), 0) AS volume,
                               COALESCE(sum(merchant_fee) FILTER (WHERE status IN ('approved', 'settled')), 0) AS fees
                          FROM bnpl_applications
                         WHERE merchant_id = :merchant AND livemode = :livemode AND created_at >= :since
                         GROUP BY provider
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("since", ts(since))
                .query((rs, n) -> new ProviderFigures(rs.getString("provider"), rs.getLong("applications"), rs.getLong("approved"),
                        rs.getLong("declined"), rs.getLong("failed"), rs.getLong("pending"), rs.getLong("abandoned"),
                        rs.getDouble("avg_ms"), rs.getDouble("p50_ms"), rs.getLong("volume"), rs.getLong("fees")))
                .list();
    }

    /** Approved applications whose provider is due to have paid by now, oldest first. */
    public List<Application> dueForSettlement(Instant approvedBefore, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM bnpl_applications WHERE status = 'approved' AND decided_at <= :before
                        ORDER BY decided_at LIMIT :limit
                        """)
                .param("before", ts(approvedBefore)).param("limit", limit).query(this::map).list();
    }

    public void markSettled(String id, Instant now) {
        jdbc.sql("UPDATE bnpl_applications SET status = 'settled', settled_at = :now, updated_at = :now WHERE id = :id")
                .param("id", id).param("now", ts(now)).update();
    }

    // --- refunds -----------------------------------------------------------------------------------------------

    public record Refund(String refundId, String applicationId, long amount, String status, String failure) {
    }

    public Optional<Refund> findRefund(String refundId) {
        return jdbc.sql("SELECT refund_id, application_id, amount, status, failure FROM bnpl_refunds WHERE refund_id = :id FOR UPDATE")
                .param("id", refundId).query((rs, n) -> new Refund(rs.getString("refund_id"), rs.getString("application_id"),
                        rs.getLong("amount"), rs.getString("status"), rs.getString("failure"))).optional();
    }

    /** What has been given back or is being given back: a failed refund returned nothing. */
    public long refundedTotal(String applicationId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM bnpl_refunds WHERE application_id = :id AND status <> 'failed'")
                .param("id", applicationId).query(Long.class).single();
    }

    public void insertRefund(String refundId, String applicationId, long amount, Instant now) {
        jdbc.sql("""
                        INSERT INTO bnpl_refunds (refund_id, application_id, amount, status, created_at, updated_at)
                        VALUES (:id, :application, :amount, 'pending', :now, :now)
                        """)
                .param("id", refundId).param("application", applicationId).param("amount", amount).param("now", ts(now)).update();
    }

    public void finishRefund(String refundId, boolean succeeded, String failure, Instant now) {
        jdbc.sql("UPDATE bnpl_refunds SET status = :status, failure = :failure, updated_at = :now WHERE refund_id = :id")
                .param("id", refundId).param("status", succeeded ? "succeeded" : "failed")
                .param("failure", failure, Types.VARCHAR).param("now", ts(now)).update();
    }

    private Application map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        long ms = rs.getLong("decision_ms");
        Long decisionMs = rs.wasNull() ? null : ms;
        List<Long> instalments = json.readValue(rs.getString("instalments"), new TypeReference<List<Long>>() { });
        return new Application(rs.getString("id"), rs.getString("payment_id"), rs.getString("merchant_id"),
                rs.getBoolean("livemode"), rs.getString("provider"), rs.getString("plan"),
                ApplicationStatus.fromWire(rs.getString("status")), rs.getString("provider_ref"), rs.getLong("amount"),
                rs.getString("currency").trim(), rs.getString("country"), rs.getInt("instalment_count"), instalments,
                rs.getInt("interval_days"), rs.getDouble("apr_percent"), rs.getLong("total_payable"),
                rs.getLong("merchant_fee"), rs.getString("customer_email"), rs.getString("decline_reason"), decisionMs,
                instant(rs, "expires_at"), instant(rs, "created_at"), instant(rs, "decided_at"), instant(rs, "settled_at"), instant(rs, "updated_at"));
    }
}
