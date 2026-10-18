package dev.cutpay.billing.settings;

import static dev.cutpay.billing.config.Sql.instant;

import dev.cutpay.billing.settings.BillingSettings.Settings;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** How unpaid invoices are retried, and the emails the platform sent about them. */
@RestController
class SettingsController {

    record SettingsView(String object, List<Integer> retryDays, String finalAction) {
        static SettingsView of(Settings s) {
            return new SettingsView("billing_settings", s.retryDays(), s.finalAction());
        }
    }

    record UpdateSettings(@NotEmpty List<Integer> retryDays, String finalAction) {
    }

    record NotificationView(String id, String object, String kind, String recipient, String subject, String body,
                            String relatedId, Instant createdAt) {
    }

    record NotificationList(String object, List<NotificationView> data) {
    }

    private final BillingSettings settings;
    private final JdbcClient jdbc;

    SettingsController(BillingSettings settings, JdbcClient jdbc) {
        this.settings = settings;
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/billing_settings")
    SettingsView get(@AuthenticationPrincipal Jwt jwt) {
        return SettingsView.of(settings.get(merchant(jwt)));
    }

    @PutMapping("/v1/billing_settings")
    SettingsView update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateSettings request) {
        List<Integer> days = request.retryDays();
        if (days.size() > 10 || days.stream().anyMatch(d -> d == null || d < 1 || d > 60)) {
            throw ApiException.invalidRequest("invalid_retry_days",
                    "Up to 10 retries, each 1 to 60 days after the first failure", "retry_days");
        }
        for (int i = 1; i < days.size(); i++) {
            if (days.get(i) <= days.get(i - 1)) {
                throw ApiException.invalidRequest("invalid_retry_days", "Retry days must be increasing", "retry_days");
            }
        }
        String action = request.finalAction() == null ? "cancel" : request.finalAction();
        if (!"cancel".equals(action) && !"unpaid".equals(action)) {
            throw ApiException.invalidRequest("invalid_final_action", "Final action must be cancel or unpaid", "final_action");
        }
        return SettingsView.of(settings.save(merchant(jwt), days, action));
    }

    /** The emails that were sent, or would have been, about one subscription or invoice. */
    @GetMapping("/v1/billing_notifications")
    NotificationList notifications(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                                   @RequestParam(name = "related_id", required = false) String relatedId) {
        boolean livemode = RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode);
        List<NotificationView> found = jdbc.sql("""
                        SELECT id, kind, recipient, subject, body, related_id, created_at FROM notifications
                         WHERE merchant_id = :merchant AND livemode = :livemode
                           AND (CAST(:related AS text) IS NULL OR related_id = :related)
                         ORDER BY created_at DESC, id DESC LIMIT 100
                        """)
                .param("merchant", merchant(jwt)).param("livemode", livemode)
                .param("related", relatedId == null || relatedId.isBlank() ? null : relatedId, java.sql.Types.VARCHAR)
                .query((rs, n) -> new NotificationView(rs.getString("id"), "notification", rs.getString("kind"),
                        rs.getString("recipient"), rs.getString("subject"), rs.getString("body"),
                        rs.getString("related_id"), instant(rs, "created_at")))
                .list();
        return new NotificationList("list", found);
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }
}
