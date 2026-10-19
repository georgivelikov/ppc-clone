package dev.cutpay.reporting.overview;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The numbers on the dashboard's home: what came in, what it cost, how often paying works, and where customers drop out. */
@Service
public class OverviewService {

    public record Money(String currency, long gross, long fees, long refunds, long disputes, long net) {
    }

    public record Count(String key, long count) {
    }

    public record Funnel(long opened, long attempted, long paid) {
    }

    public record Overview(int days, List<Money> money, long succeeded, long failed, Double successRate,
                           List<Count> declineReasons, List<Count> methodMix, Funnel funnel) {
    }

    private final JdbcClient jdbc;

    public OverviewService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Overview overview(String merchantId, boolean livemode, int days) {
        Timestamp since = Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
        List<Money> money = jdbc.sql("""
                        SELECT currency,
                               COALESCE(SUM(gross) FILTER (WHERE type = 'payment'), 0) AS gross,
                               COALESCE(SUM(fee) FILTER (WHERE type = 'payment'), 0)
                                 - COALESCE(SUM(net) FILTER (WHERE type IN ('dispute_fee', 'dispute_fee_reversal')), 0) AS fees,
                               COALESCE(SUM(gross) FILTER (WHERE type = 'refund'), 0) AS refunds,
                               COALESCE(SUM(gross) FILTER (WHERE type IN ('dispute', 'dispute_reversal')), 0) AS disputes,
                               COALESCE(SUM(net) FILTER (WHERE type IN ('payment', 'refund', 'dispute', 'dispute_fee', 'dispute_reversal', 'dispute_fee_reversal')), 0) AS net
                        FROM fact_balance_transactions
                        WHERE merchant_id = :m AND livemode = :l AND created_at >= :since
                        GROUP BY currency ORDER BY currency
                        """)
                .param("m", merchantId).param("l", livemode).param("since", since)
                .query((rs, n) -> new Money(rs.getString("currency"), rs.getLong("gross"), rs.getLong("fees"), rs.getLong("refunds"),
                        rs.getLong("disputes"), rs.getLong("net")))
                .list();
        long succeeded = count("status = 'succeeded'", merchantId, livemode, since);
        long failed = count("status = 'failed'", merchantId, livemode, since);
        List<Count> reasons = jdbc.sql("""
                        SELECT failure_code, count(*) FROM fact_payments
                        WHERE merchant_id = :m AND livemode = :l AND created_at >= :since AND status = 'failed' AND failure_code IS NOT NULL
                        GROUP BY failure_code ORDER BY count(*) DESC, failure_code LIMIT 5
                        """)
                .param("m", merchantId).param("l", livemode).param("since", since)
                .query((rs, n) -> new Count(rs.getString(1), rs.getLong(2))).list();
        List<Count> mix = jdbc.sql("""
                        SELECT COALESCE(method_type, 'card'), count(*) FROM fact_payments
                        WHERE merchant_id = :m AND livemode = :l AND created_at >= :since AND status = 'succeeded'
                        GROUP BY 1 ORDER BY count(*) DESC, 1
                        """)
                .param("m", merchantId).param("l", livemode).param("since", since)
                .query((rs, n) -> new Count(rs.getString(1), rs.getLong(2))).list();
        Funnel funnel = jdbc.sql("""
                        SELECT count(*), count(attempted_at), count(succeeded_at) FROM fact_payments
                        WHERE merchant_id = :m AND livemode = :l AND created_at >= :since
                        """)
                .param("m", merchantId).param("l", livemode).param("since", since)
                .query((rs, n) -> new Funnel(rs.getLong(1), rs.getLong(2), rs.getLong(3))).single();
        Double rate = succeeded + failed == 0 ? null : (double) succeeded / (succeeded + failed);
        return new Overview(days, money, succeeded, failed, rate, reasons, mix, funnel);
    }

    private long count(String where, String merchantId, boolean livemode, Timestamp since) {
        return jdbc.sql("SELECT count(*) FROM fact_payments WHERE merchant_id = :m AND livemode = :l AND created_at >= :since AND " + where)
                .param("m", merchantId).param("l", livemode).param("since", since).query(Long.class).single();
    }
}
