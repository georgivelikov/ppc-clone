package dev.cutpay.identity.staff;

import static dev.cutpay.identity.staff.StaffService.requireAdmin;
import static dev.cutpay.identity.staff.StaffService.requireStaff;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.identity.audit.AuditLog;
import dev.cutpay.identity.domain.Merchant;
import dev.cutpay.identity.onboarding.OnboardingService;
import dev.cutpay.identity.onboarding.OnboardingService.Application;
import dev.cutpay.identity.onboarding.OnboardingService.DocumentFile;
import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What Cutpay staff can do about a business: find it, review its application, mark it, hold its payouts. Reviewers read,
 * review and mark; holding payouts needs an admin. Every change is written to the audit trail under the business, with the
 * staff member as the actor, so the business sees "Cutpay staff" in its own activity.
 */
@RestController
class StaffController {

    private static final Pattern FLAG = Pattern.compile("^[a-z][a-z_]{2,30}$");
    static final String FROZEN = "payouts_frozen";

    record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    record MerchantRow(String id, String displayName, String legalName, String country, String status, String ownerEmail,
                       Instant created) {
    }

    record Flag(String flag, String note, String setBy, Instant setAt) {
    }

    record MerchantDetail(MerchantRow merchant, List<Flag> flags, boolean payoutsFrozen, Map<String, Object> application,
                          List<Map<String, Object>> activity) {
    }

    record QueueRow(String merchantId, String merchantName, String status, Integer riskScore, boolean manualReview, Instant submittedAt) {
    }

    record DecisionRequest(@NotBlank String decision, String reason) {
    }

    record NoteRequest(String note) {
    }

    private final StaffService staff;
    private final JdbcClient jdbc;
    private final OnboardingService onboarding;
    private final AuditLog audit;
    private final OutboxWriter outbox;

    StaffController(StaffService staff, JdbcClient jdbc, OnboardingService onboarding, AuditLog audit, OutboxWriter outbox) {
        this.staff = staff;
        this.jdbc = jdbc;
        this.onboarding = onboarding;
        this.audit = audit;
        this.outbox = outbox;
    }

    @PostMapping("/auth/staff/login")
    StaffService.Session login(@Valid @RequestBody LoginRequest request) {
        return staff.login(request.email(), request.password());
    }

    @GetMapping("/v1/staff/me")
    Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        requireStaff(jwt);
        return Map.of("id", jwt.getSubject(), "email", jwt.getClaimAsString("email"), "role", jwt.getClaimAsString("role"));
    }

    // --- finding a business -----------------------------------------------------------------------------------

    @GetMapping("/v1/staff/merchants")
    Map<String, Object> search(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "") String q,
                               @RequestParam(required = false) String status) {
        requireStaff(jwt);
        String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        List<MerchantRow> rows = jdbc.sql("""
                        SELECT m.id, m.display_name, m.legal_name, m.country, m.status, m.created_at,
                               (SELECT u.email FROM memberships ms JOIN users u ON u.id = ms.user_id
                                 WHERE ms.merchant_id = m.id AND ms.role = 'OWNER' ORDER BY ms.created_at LIMIT 1) AS owner_email
                        FROM merchants m
                        WHERE (:q = '%%' OR lower(m.display_name) LIKE :q OR lower(m.legal_name) LIKE :q OR m.id = :exact
                               OR EXISTS (SELECT 1 FROM memberships ms JOIN users u ON u.id = ms.user_id
                                           WHERE ms.merchant_id = m.id AND lower(u.email) LIKE :q))
                          AND (CAST(:status AS TEXT) IS NULL OR m.status = :status)
                        ORDER BY m.created_at DESC LIMIT 50
                        """)
                .param("q", like).param("exact", q.trim()).param("status", status == null ? null : status.toUpperCase(Locale.ROOT), java.sql.Types.VARCHAR)
                .query((rs, n) -> row(rs)).list();
        return Map.of("object", "list", "data", rows);
    }

    @GetMapping("/v1/staff/merchants/{id}")
    @Transactional // not read-only: looking at a business that never started an application starts an empty one
    MerchantDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        requireStaff(jwt);
        MerchantRow merchant = jdbc.sql("""
                        SELECT m.id, m.display_name, m.legal_name, m.country, m.status, m.created_at,
                               (SELECT u.email FROM memberships ms JOIN users u ON u.id = ms.user_id
                                 WHERE ms.merchant_id = m.id AND ms.role = 'OWNER' ORDER BY ms.created_at LIMIT 1) AS owner_email
                        FROM merchants m WHERE m.id = :id
                        """).param("id", id).query((rs, n) -> row(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("merchant_not_found", "No such business"));
        List<Flag> flags = flags(id);
        Application app = onboarding.view(id);
        Map<String, Object> summary = Map.of("status", app.status(), "risk_score", app.riskScore() == null ? "" : app.riskScore(),
                "manual_review", app.manualReview(), "people", app.persons().size());
        var activity = jdbc.sql("""
                        SELECT id, actor_email, action, detail, created_at FROM audit_events WHERE merchant_id = :m ORDER BY id DESC LIMIT 20
                        """).param("m", id)
                .query((rs, n) -> Map.<String, Object>of("id", rs.getLong("id"), "actor", String.valueOf(rs.getString("actor_email")),
                        "action", rs.getString("action"), "detail", String.valueOf(rs.getString("detail")),
                        "created_at", rs.getTimestamp("created_at").toInstant().toString()))
                .list();
        return new MerchantDetail(merchant, flags, flags.stream().anyMatch(f -> f.flag().equals(FROZEN)), summary, activity);
    }

    // --- the review queue -------------------------------------------------------------------------------------

    /** Applications waiting for a person first (the risky ones), oldest first. */
    @GetMapping("/v1/staff/applications")
    Map<String, Object> queue(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "in_review") String status) {
        requireStaff(jwt);
        List<QueueRow> rows = jdbc.sql("""
                        SELECT a.merchant_id, m.display_name, m.status, a.risk_score, a.manual_review, a.submitted_at
                        FROM onboarding_applications a JOIN merchants m ON m.id = a.merchant_id
                        WHERE m.status = :status AND a.submitted_at IS NOT NULL
                        ORDER BY a.manual_review DESC, a.submitted_at LIMIT 100
                        """).param("status", status.toUpperCase(Locale.ROOT))
                .query((rs, n) -> new QueueRow(rs.getString(1), rs.getString(2), rs.getString(3).toLowerCase(Locale.ROOT),
                        (Integer) rs.getObject(4), rs.getBoolean(5), rs.getTimestamp(6).toInstant()))
                .list();
        return Map.of("object", "list", "data", rows);
    }

    @GetMapping("/v1/staff/applications/{merchantId}")
    @Transactional
    Application application(@AuthenticationPrincipal Jwt jwt, @PathVariable String merchantId) {
        requireStaff(jwt);
        return onboarding.view(merchantId);
    }

    @GetMapping("/v1/staff/applications/{merchantId}/documents/{documentId}")
    ResponseEntity<byte[]> document(@AuthenticationPrincipal Jwt jwt, @PathVariable String merchantId, @PathVariable String documentId) {
        requireStaff(jwt);
        DocumentFile file = onboarding.documentFile(merchantId, documentId).orElseThrow(() -> ApiException.notFound("document_not_found", "No such document"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.content());
    }

    /** Approve, reject or ask for more. The reason goes to the business in the email, so it is required unless approving. */
    @PostMapping("/v1/staff/applications/{merchantId}/decision")
    @Transactional
    Application decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String merchantId, @Valid @RequestBody DecisionRequest request) {
        requireStaff(jwt);
        Merchant.Status status = switch (request.decision()) {
            case "approve" -> Merchant.Status.ACTIVE;
            case "reject" -> Merchant.Status.REJECTED;
            case "request_info" -> Merchant.Status.NEEDS_MORE_INFO;
            default -> throw ApiException.invalidRequest("invalid_decision", "Decide approve, reject or request_info", "decision");
        };
        if (status != Merchant.Status.ACTIVE && (request.reason() == null || request.reason().trim().length() < 5)) {
            throw ApiException.invalidRequest("reason_required", "Say why, so the business knows what to do", "reason");
        }
        onboarding.decide(merchantId, status, request.reason() == null ? null : request.reason().trim(), jwt.getClaimAsString("email"));
        audit.record(merchantId, jwt.getSubject(), jwt.getClaimAsString("email"), "staff.application_" + request.decision(), "merchant",
                merchantId, request.reason());
        return onboarding.view(merchantId);
    }

    // --- marks and holds --------------------------------------------------------------------------------------

    @PutMapping("/v1/staff/merchants/{id}/flags/{flag}")
    @Transactional
    List<Flag> setFlag(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String flag, @RequestBody(required = false) NoteRequest request) {
        requireStaff(jwt);
        if (!FLAG.matcher(flag).matches() || flag.equals(FROZEN)) {
            throw ApiException.invalidRequest("invalid_flag", "A flag is a lowercase word such as watchlist. Payouts are held with the freeze action.", "flag");
        }
        requireMerchant(id);
        jdbc.sql("""
                        INSERT INTO merchant_flags (merchant_id, flag, note, set_by) VALUES (:m, :f, :n, :by)
                        ON CONFLICT (merchant_id, flag) DO UPDATE SET note = EXCLUDED.note, set_by = EXCLUDED.set_by, set_at = now()
                        """)
                .param("m", id).param("f", flag).param("n", request == null ? null : request.note(), java.sql.Types.VARCHAR)
                .param("by", jwt.getClaimAsString("email")).update();
        audit.record(id, jwt.getSubject(), jwt.getClaimAsString("email"), "staff.flag_set", "merchant", id, flag);
        return flags(id);
    }

    @DeleteMapping("/v1/staff/merchants/{id}/flags/{flag}")
    @Transactional
    List<Flag> clearFlag(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String flag) {
        requireStaff(jwt);
        if (flag.equals(FROZEN)) {
            throw ApiException.invalidRequest("invalid_flag", "Use the unfreeze action", "flag");
        }
        jdbc.sql("DELETE FROM merchant_flags WHERE merchant_id = :m AND flag = :f").param("m", id).param("f", flag).update();
        audit.record(id, jwt.getSubject(), jwt.getClaimAsString("email"), "staff.flag_cleared", "merchant", id, flag);
        return flags(id);
    }

    @PostMapping("/v1/staff/merchants/{id}/payouts/freeze")
    @Transactional
    MerchantDetail freeze(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody NoteRequest request) {
        requireAdmin(jwt);
        requireMerchant(id);
        if (request == null || request.note() == null || request.note().trim().length() < 5) {
            throw ApiException.invalidRequest("reason_required", "Write why payouts are held", "note");
        }
        jdbc.sql("""
                        INSERT INTO merchant_flags (merchant_id, flag, note, set_by) VALUES (:m, :f, :n, :by)
                        ON CONFLICT (merchant_id, flag) DO UPDATE SET note = EXCLUDED.note, set_by = EXCLUDED.set_by, set_at = now()
                        """)
                .param("m", id).param("f", FROZEN).param("n", request.note().trim()).param("by", jwt.getClaimAsString("email")).update();
        outbox.write("merchant.events", "PayoutsFrozen", id, id, null, Map.of("merchant_id", id, "reason", request.note().trim()));
        audit.record(id, jwt.getSubject(), jwt.getClaimAsString("email"), "staff.payouts_frozen", "merchant", id, request.note().trim());
        return detail(jwt, id);
    }

    @PostMapping("/v1/staff/merchants/{id}/payouts/unfreeze")
    @Transactional
    MerchantDetail unfreeze(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        requireAdmin(jwt);
        requireMerchant(id);
        jdbc.sql("DELETE FROM merchant_flags WHERE merchant_id = :m AND flag = :f").param("m", id).param("f", FROZEN).update();
        outbox.write("merchant.events", "PayoutsUnfrozen", id, id, null, Map.of("merchant_id", id));
        audit.record(id, jwt.getSubject(), jwt.getClaimAsString("email"), "staff.payouts_unfrozen", "merchant", id, null);
        return detail(jwt, id);
    }

    // --- everything staff did ---------------------------------------------------------------------------------

    @GetMapping("/v1/staff/audit")
    Map<String, Object> staffAudit(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long before,
                                   @RequestParam(defaultValue = "50") int limit) {
        requireStaff(jwt);
        var rows = jdbc.sql("""
                        SELECT id, merchant_id, actor_email, action, detail, created_at FROM audit_events
                        WHERE actor_id LIKE 'stf\\_%' AND (CAST(:before AS BIGINT) IS NULL OR id < :before)
                        ORDER BY id DESC LIMIT :limit
                        """)
                .param("before", before, java.sql.Types.BIGINT).param("limit", Math.max(1, Math.min(limit, 100)))
                .query((rs, n) -> Map.<String, Object>of("id", rs.getLong("id"), "merchant_id", rs.getString("merchant_id"),
                        "actor", String.valueOf(rs.getString("actor_email")), "action", rs.getString("action"),
                        "detail", String.valueOf(rs.getString("detail")), "created_at", rs.getTimestamp("created_at").toInstant().toString()))
                .list();
        return Map.of("object", "list", "data", rows);
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private void requireMerchant(String id) {
        if (jdbc.sql("SELECT count(*) FROM merchants WHERE id = :id").param("id", id).query(Integer.class).single() == 0) {
            throw ApiException.notFound("merchant_not_found", "No such business");
        }
    }

    private List<Flag> flags(String merchantId) {
        return jdbc.sql("SELECT flag, note, set_by, set_at FROM merchant_flags WHERE merchant_id = :m ORDER BY set_at")
                .param("m", merchantId)
                .query((rs, n) -> new Flag(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant())).list();
    }

    private static MerchantRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MerchantRow(rs.getString("id"), rs.getString("display_name"), rs.getString("legal_name"), rs.getString("country"),
                rs.getString("status").toLowerCase(Locale.ROOT), rs.getString("owner_email"), rs.getTimestamp("created_at").toInstant());
    }
}
