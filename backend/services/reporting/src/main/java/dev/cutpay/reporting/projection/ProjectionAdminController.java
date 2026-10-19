package dev.cutpay.reporting.projection;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator tools. The gateway does not route {@code /admin/**}. */
@RestController
class ProjectionAdminController {

    private final Rebuilder rebuilder;
    private final JdbcClient jdbc;

    ProjectionAdminController(Rebuilder rebuilder, JdbcClient jdbc) {
        this.rebuilder = rebuilder;
        this.jdbc = jdbc;
    }

    /** Drops the read model and replays the topics into it. */
    @PostMapping("/admin/rebuild")
    Rebuilder.Result rebuild() {
        return rebuilder.rebuild();
    }

    /**
     * A fingerprint of the read model: for each table the number of rows, the sums that matter and a hash of every row. Two read
     * models with the same fingerprint hold the same numbers.
     */
    @GetMapping("/admin/checksum")
    Map<String, Object> checksum() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payments", row("fact_payments", "payment_id", "SUM(amount)", "payment_id || status || amount || currency"));
        out.put("balance_transactions", row("fact_balance_transactions", "transaction_id", "SUM(gross) || '/' || SUM(fee) || '/' || SUM(net)",
                "transaction_id || type || gross || fee || net || currency"));
        out.put("payouts", row("fact_payouts", "payout_id", "SUM(amount)", "payout_id || status || amount || fee"));
        out.put("disputes", row("fact_disputes", "dispute_id", "SUM(amount)", "dispute_id || status || amount || fee"));
        return out;
    }

    private Map<String, Object> row(String table, String key, String sums, String rowText) {
        return jdbc.sql("SELECT count(*) AS row_count, COALESCE(CAST(" + sums + " AS TEXT), '') AS sums, "
                        + "COALESCE(md5(string_agg(" + rowText + ", ',' ORDER BY " + key + ")), '') AS hash FROM " + table)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("rows", rs.getLong("row_count"));
                    m.put("sums", rs.getString("sums"));
                    m.put("hash", rs.getString("hash"));
                    return m;
                }).single();
    }
}
