package dev.cutpay.ledger.api;

import dev.cutpay.ledger.posting.LedgerService;
import dev.cutpay.money.SupportedCurrencies;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Corrections to a merchant's balance by Cutpay staff, with four eyes. An admin asks for one; a different admin approves or
 * rejects it. The books change only when it is approved, and the database refuses an approval by the person who asked.
 */
@RestController
class AdjustmentController {

    record Request(@NotBlank String merchantId, @NotBlank String currency, long amount, @NotBlank String reason, Boolean livemode) {
    }

    record Decision(String note) {
    }

    record View(String object, String id, String merchantId, boolean livemode, String currency, long amount, String reason,
                String status, String requestedBy, String decidedBy, String decisionNote, Instant requestedAt, Instant decidedAt) {
    }

    private final JdbcClient jdbc;
    private final LedgerService ledger;

    AdjustmentController(JdbcClient jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    private static void requireAdmin(Jwt jwt) {
        if (!"staff_admin".equals(jwt.getClaimAsString("role")) || !"staff".equals(jwt.getClaimAsString("realm"))) {
            throw ApiException.forbidden("staff_admin_only", "Only back-office admins can adjust balances");
        }
    }

    @PostMapping("/v1/staff/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    View request(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Request request) {
        requireAdmin(jwt);
        if (!SupportedCurrencies.isSupported(request.currency())) {
            throw ApiException.invalidRequest("unsupported_currency", "Currency is not supported", "currency");
        }
        if (request.amount() == 0 || Math.abs(request.amount()) > 100_000_000L) {
            throw ApiException.invalidRequest("invalid_amount", "The amount must not be zero and at most 1,000,000.00", "amount");
        }
        if (request.reason().trim().length() < 10) {
            throw ApiException.invalidRequest("reason_required", "Explain the correction in at least 10 characters", "reason");
        }
        String id = Ids.generate("adj");
        jdbc.sql("""
                        INSERT INTO adjustments (id, merchant_id, livemode, currency, amount, reason, status, requested_by)
                        VALUES (:id, :m, :l, :c, :a, :r, 'pending', :by)
                        """)
                .param("id", id).param("m", request.merchantId().trim()).param("l", Boolean.TRUE.equals(request.livemode()))
                .param("c", request.currency().toUpperCase()).param("a", request.amount()).param("r", request.reason().trim())
                .param("by", jwt.getClaimAsString("email")).update();
        return get(id);
    }

    @GetMapping("/v1/staff/adjustments")
    Map<String, Object> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status) {
        if (!"staff".equals(jwt.getClaimAsString("realm"))) {
            throw ApiException.forbidden("staff_only", "This is for Cutpay staff");
        }
        List<View> rows = jdbc.sql("SELECT id FROM adjustments WHERE (CAST(:s AS TEXT) IS NULL OR status = :s) ORDER BY requested_at DESC LIMIT 100")
                .param("s", status, java.sql.Types.VARCHAR).query(String.class).list().stream().map(this::get).toList();
        return Map.of("object", "list", "data", rows);
    }

    @PostMapping("/v1/staff/adjustments/{id}/approve")
    @Transactional
    View approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody(required = false) Decision decision) {
        requireAdmin(jwt);
        View pending = lockPending(id);
        if (pending.requestedBy().equalsIgnoreCase(jwt.getClaimAsString("email"))) {
            throw ApiException.forbidden("four_eyes", "A different admin must approve this: you asked for it");
        }
        jdbc.sql("UPDATE adjustments SET status = 'approved', decided_by = :by, decision_note = :n, decided_at = now() WHERE id = :id")
                .param("by", jwt.getClaimAsString("email")).param("n", decision == null ? null : decision.note(), java.sql.Types.VARCHAR)
                .param("id", id).update();
        ledger.postAdjustment(id, pending.merchantId(), pending.livemode(), pending.currency(), pending.amount(), pending.reason());
        return get(id);
    }

    @PostMapping("/v1/staff/adjustments/{id}/reject")
    @Transactional
    View reject(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody(required = false) Decision decision) {
        requireAdmin(jwt);
        View pending = lockPending(id);
        if (pending.requestedBy().equalsIgnoreCase(jwt.getClaimAsString("email"))) {
            throw ApiException.forbidden("four_eyes", "A different admin must decide this: you asked for it. Ask for a new one instead.");
        }
        jdbc.sql("UPDATE adjustments SET status = 'rejected', decided_by = :by, decision_note = :n, decided_at = now() WHERE id = :id")
                .param("by", jwt.getClaimAsString("email")).param("n", decision == null ? null : decision.note(), java.sql.Types.VARCHAR)
                .param("id", id).update();
        return get(id);
    }

    private View lockPending(String id) {
        jdbc.sql("SELECT id FROM adjustments WHERE id = :id FOR UPDATE").param("id", id).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("adjustment_not_found", "No such adjustment"));
        View v = get(id);
        if (!"pending".equals(v.status())) {
            throw ApiException.conflict("already_decided", "This adjustment was already " + v.status(), null);
        }
        return v;
    }

    private View get(String id) {
        return jdbc.sql("""
                        SELECT id, merchant_id, livemode, currency, amount, reason, status, requested_by, decided_by, decision_note,
                               requested_at, decided_at FROM adjustments WHERE id = :id
                        """).param("id", id)
                .query((rs, n) -> new View("adjustment", rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getLong(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getTimestamp(11).toInstant(), rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant()))
                .single();
    }
}
