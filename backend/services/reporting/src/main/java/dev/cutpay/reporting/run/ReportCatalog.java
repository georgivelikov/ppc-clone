package dev.cutpay.reporting.run;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The reports a business can ask for. Each is a query over the read model with named columns; asking for fewer columns
 * leaves the others out. Money is written as a decimal number (12.34), never in the smallest unit, and dates as ISO text.
 */
@Component
public class ReportCatalog {

    public record Column(String key, String label) {
    }

    public record ReportType(String id, String title, String about, List<Column> columns) {
    }

    private interface Query {
        List<Map<String, Object>> run(JdbcClient jdbc, String merchantId, boolean livemode, Timestamp from, Timestamp to);
    }

    private record Definition(ReportType type, Query query) {
    }

    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    private static Column c(String key, String label) {
        return new Column(key, label);
    }

    static String money(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static String day(Timestamp t) {
        return t == null ? "" : t.toInstant().atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    private static String time(Timestamp t) {
        return t == null ? "" : t.toInstant().toString();
    }

    private static void define(String id, String title, String about, List<Column> columns, Query query) {
        DEFINITIONS.put(id, new Definition(new ReportType(id, title, about, columns), query));
    }

    private static List<Map<String, Object>> rows(JdbcClient.StatementSpec spec, RowFn fn) {
        return spec.query((rs, n) -> fn.row(rs)).list();
    }

    private interface RowFn {
        Map<String, Object> row(ResultSet rs) throws SQLException;
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    static {
        define("balance_summary", "Balance summary", "Per day and currency: payments, fees, refunds, disputes and the net change.",
                List.of(c("date", "Date"), c("currency", "Currency"), c("payments", "Payments"), c("fees", "Fees"), c("refunds", "Refunds"),
                        c("disputes", "Disputes"), c("net", "Net change")),
                (jdbc, m, live, from, to) -> rows(jdbc.sql("""
                                SELECT (created_at AT TIME ZONE 'UTC')::date AS day, currency,
                                       COALESCE(SUM(gross) FILTER (WHERE type = 'payment'), 0) AS payments,
                                       COALESCE(SUM(fee) FILTER (WHERE type = 'payment'), 0)
                                         - COALESCE(SUM(net) FILTER (WHERE type IN ('dispute_fee', 'dispute_fee_reversal')), 0) AS fees,
                                       COALESCE(SUM(gross) FILTER (WHERE type = 'refund'), 0) AS refunds,
                                       COALESCE(SUM(gross) FILTER (WHERE type IN ('dispute', 'dispute_reversal')), 0) AS disputes,
                                       COALESCE(SUM(net), 0) AS net
                                FROM fact_balance_transactions
                                WHERE merchant_id = :m AND livemode = :l AND created_at >= :from AND created_at < :to
                                GROUP BY 1, 2 ORDER BY 1, 2
                                """).param("m", m).param("l", live).param("from", from).param("to", to),
                        rs -> row("date", rs.getDate("day").toString(), "currency", rs.getString("currency"),
                                "payments", money(rs.getLong("payments")), "fees", money(rs.getLong("fees")),
                                "refunds", money(rs.getLong("refunds")), "disputes", money(rs.getLong("disputes")),
                                "net", money(rs.getLong("net")))));

        define("payments", "Payments", "Every payment started in the period, with its outcome.",
                List.of(c("payment_id", "Payment"), c("created", "Created"), c("status", "Status"), c("currency", "Currency"),
                        c("amount", "Amount"), c("method", "Method"), c("card_brand", "Card brand"), c("failure_code", "Failure")),
                (jdbc, m, live, from, to) -> rows(jdbc.sql("""
                                SELECT payment_id, created_at, status, currency, amount, method_type, card_brand, failure_code
                                FROM fact_payments WHERE merchant_id = :m AND livemode = :l AND created_at >= :from AND created_at < :to
                                ORDER BY created_at, payment_id
                                """).param("m", m).param("l", live).param("from", from).param("to", to),
                        rs -> row("payment_id", rs.getString("payment_id"), "created", time(rs.getTimestamp("created_at")),
                                "status", rs.getString("status"), "currency", rs.getString("currency"),
                                "amount", money(rs.getLong("amount")), "method", orEmpty(rs.getString("method_type"), "card"),
                                "card_brand", orEmpty(rs.getString("card_brand"), ""), "failure_code", orEmpty(rs.getString("failure_code"), ""))));

        define("refunds", "Refunds", "Money returned to customers.",
                List.of(c("refund_id", "Refund"), c("created", "Created"), c("payment_id", "Payment"), c("currency", "Currency"), c("amount", "Amount")),
                (jdbc, m, live, from, to) -> rows(typed(jdbc, "refund", m, live, from, to),
                        rs -> row("refund_id", rs.getString("source_id"), "created", time(rs.getTimestamp("created_at")),
                                "payment_id", orEmpty(rs.getString("parent_id"), ""), "currency", rs.getString("currency"),
                                "amount", money(-rs.getLong("gross")))));

        define("fees", "Fees", "What each payment cost: the amount, the fee and what was left.",
                List.of(c("payment_id", "Payment"), c("created", "Created"), c("currency", "Currency"), c("gross", "Amount"), c("fee", "Fee"), c("net", "Net")),
                (jdbc, m, live, from, to) -> rows(typed(jdbc, "payment", m, live, from, to),
                        rs -> row("payment_id", rs.getString("source_id"), "created", time(rs.getTimestamp("created_at")),
                                "currency", rs.getString("currency"), "gross", money(rs.getLong("gross")),
                                "fee", money(rs.getLong("fee")), "net", money(rs.getLong("net")))));

        define("payout_reconciliation", "Payout reconciliation",
                "Each payout next to what the ledger took for it. The difference should always be 0.00.",
                List.of(c("payout_id", "Payout"), c("created", "Created"), c("arrival_date", "Arrival"), c("status", "Status"), c("kind", "Kind"),
                        c("currency", "Currency"), c("amount", "Amount"), c("fee", "Fee"), c("ledger_amount", "Taken by the ledger"),
                        c("difference", "Difference")),
                (jdbc, m, live, from, to) -> rows(jdbc.sql("""
                                SELECT p.payout_id, p.created_at, p.arrival_date, p.status, p.kind, p.currency, p.amount, p.fee,
                                       COALESCE(-(SELECT SUM(b.net) FROM fact_balance_transactions b
                                                   WHERE b.source_id = p.payout_id AND b.type IN ('payout', 'payout_failure')), 0) AS ledger_amount
                                FROM fact_payouts p WHERE p.merchant_id = :m AND p.livemode = :l AND p.created_at >= :from AND p.created_at < :to
                                ORDER BY p.created_at, p.payout_id
                                """).param("m", m).param("l", live).param("from", from).param("to", to),
                        rs -> {
                            long expected = "failed".equals(rs.getString("status")) ? 0 : rs.getLong("amount") + rs.getLong("fee");
                            return row("payout_id", rs.getString("payout_id"), "created", time(rs.getTimestamp("created_at")),
                                    "arrival_date", orEmpty(rs.getString("arrival_date"), ""), "status", rs.getString("status"),
                                    "kind", orEmpty(rs.getString("kind"), ""), "currency", rs.getString("currency"),
                                    "amount", money(rs.getLong("amount")), "fee", money(rs.getLong("fee")),
                                    "ledger_amount", money(rs.getLong("ledger_amount")),
                                    "difference", money(rs.getLong("ledger_amount") - expected));
                        }));

        define("disputes", "Disputes", "Disputes opened in the period and how they ended.",
                List.of(c("dispute_id", "Dispute"), c("payment_id", "Payment"), c("opened", "Opened"), c("closed", "Closed"), c("status", "Status"),
                        c("currency", "Currency"), c("amount", "Amount"), c("fee", "Fee")),
                (jdbc, m, live, from, to) -> rows(jdbc.sql("""
                                SELECT dispute_id, payment_id, created_at, closed_at, status, currency, amount, fee
                                FROM fact_disputes WHERE merchant_id = :m AND livemode = :l AND created_at >= :from AND created_at < :to
                                ORDER BY created_at, dispute_id
                                """).param("m", m).param("l", live).param("from", from).param("to", to),
                        rs -> row("dispute_id", rs.getString("dispute_id"), "payment_id", rs.getString("payment_id"),
                                "opened", time(rs.getTimestamp("created_at")), "closed", time(rs.getTimestamp("closed_at")),
                                "status", rs.getString("status"), "currency", rs.getString("currency"),
                                "amount", money(rs.getLong("amount")), "fee", money(rs.getLong("fee")))));

        define("bnpl", "Instalment payments", "Payments customers paid with buy-now-pay-later providers.",
                List.of(c("payment_id", "Payment"), c("created", "Created"), c("status", "Status"), c("currency", "Currency"), c("amount", "Amount")),
                (jdbc, m, live, from, to) -> rows(jdbc.sql("""
                                SELECT payment_id, created_at, status, currency, amount FROM fact_payments
                                WHERE merchant_id = :m AND livemode = :l AND method_type = 'bnpl' AND created_at >= :from AND created_at < :to
                                ORDER BY created_at, payment_id
                                """).param("m", m).param("l", live).param("from", from).param("to", to),
                        rs -> row("payment_id", rs.getString("payment_id"), "created", time(rs.getTimestamp("created_at")),
                                "status", rs.getString("status"), "currency", rs.getString("currency"), "amount", money(rs.getLong("amount")))));

        define("monthly_statement", "Monthly statement",
                "Per month and currency: what was sold, refunded, charged in fees and disputes, paid out, and the net change. For your accountant.",
                List.of(c("month", "Month"), c("currency", "Currency"), c("sales", "Sales"), c("refunds", "Refunds"), c("fees", "Fees"),
                        c("disputes", "Disputes"), c("payouts", "Payouts"), c("net", "Net change")),
                (jdbc, m, live, from, to) -> rows(jdbc.sql("""
                                SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM') AS month, currency,
                                       COALESCE(SUM(gross) FILTER (WHERE type = 'payment'), 0) AS sales,
                                       COALESCE(SUM(gross) FILTER (WHERE type = 'refund'), 0) AS refunds,
                                       COALESCE(SUM(fee) FILTER (WHERE type = 'payment'), 0)
                                         - COALESCE(SUM(net) FILTER (WHERE type IN ('dispute_fee', 'dispute_fee_reversal')), 0) AS fees,
                                       COALESCE(SUM(gross) FILTER (WHERE type IN ('dispute', 'dispute_reversal')), 0) AS disputes,
                                       COALESCE(SUM(net) FILTER (WHERE type IN ('payout', 'payout_failure')), 0) AS payouts,
                                       COALESCE(SUM(net), 0) AS net
                                FROM fact_balance_transactions
                                WHERE merchant_id = :m AND livemode = :l AND created_at >= :from AND created_at < :to
                                GROUP BY 1, 2 ORDER BY 1, 2
                                """).param("m", m).param("l", live).param("from", from).param("to", to),
                        rs -> row("month", rs.getString("month"), "currency", rs.getString("currency"), "sales", money(rs.getLong("sales")),
                                "refunds", money(rs.getLong("refunds")), "fees", money(rs.getLong("fees")),
                                "disputes", money(rs.getLong("disputes")), "payouts", money(rs.getLong("payouts")),
                                "net", money(rs.getLong("net")))));
    }

    private static JdbcClient.StatementSpec typed(JdbcClient jdbc, String type, String m, boolean live, Timestamp from, Timestamp to) {
        return jdbc.sql("""
                        SELECT source_id, parent_id, currency, gross, fee, net, created_at FROM fact_balance_transactions
                        WHERE merchant_id = :m AND livemode = :l AND type = :type AND created_at >= :from AND created_at < :to
                        ORDER BY created_at, transaction_id
                        """).param("m", m).param("l", live).param("type", type).param("from", from).param("to", to);
    }

    private static String orEmpty(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private final JdbcClient jdbc;

    public ReportCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReportType> types() {
        return DEFINITIONS.values().stream().map(Definition::type).toList();
    }

    public Optional<ReportType> type(String id) {
        return Optional.ofNullable(DEFINITIONS.get(id)).map(Definition::type);
    }

    /** The rows for the days from {@code start} to {@code end}, both included, in UTC. */
    public List<Map<String, Object>> run(String typeId, String merchantId, boolean livemode, LocalDate start, LocalDate end) {
        Definition d = DEFINITIONS.get(typeId);
        return new ArrayList<>(d.query().run(jdbc, merchantId, livemode, Timestamp.from(start.atStartOfDay().toInstant(ZoneOffset.UTC)),
                Timestamp.from(end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))));
    }
}
