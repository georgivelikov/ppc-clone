package dev.cutpay.payments.dispute;

import dev.cutpay.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Oversight for Cutpay staff: every dispute of every business, read only. Deciding stays with the card network. */
@RestController
class StaffDisputeController {

    record Row(String id, String merchantId, boolean livemode, String paymentId, long amount, String currency, long fee, String status,
               Instant evidenceDueBy, Instant opened) {
    }

    private final JdbcClient jdbc;

    StaffDisputeController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/staff/disputes")
    Map<String, Object> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status) {
        if (!"staff".equals(jwt.getClaimAsString("realm"))) {
            throw ApiException.forbidden("staff_only", "This is for Cutpay staff");
        }
        List<Row> rows = jdbc.sql("""
                        SELECT id, merchant_id, livemode, payment_id, amount, currency, fee, status, evidence_due_by, opened_at
                        FROM disputes WHERE (CAST(:s AS TEXT) IS NULL OR status = :s) ORDER BY opened_at DESC LIMIT 100
                        """)
                .param("s", status, java.sql.Types.VARCHAR)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getLong(5),
                        rs.getString(6), rs.getLong(7), rs.getString(8), rs.getTimestamp(9).toInstant(), rs.getTimestamp(10).toInstant()))
                .list();
        return Map.of("object", "list", "data", rows);
    }
}
