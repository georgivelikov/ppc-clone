package dev.cutpay.notifications.log;

import dev.cutpay.notifications.log.NotificationService.Entry;
import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    record PreferenceView(String template, boolean enabled) {
    }

    record PreferenceList(String object, String email, List<PreferenceView> data) {
    }

    record UpdatePreference(@NotBlank String template, boolean enabled) {
    }

    record EntryView(String object, String id, String recipient, String template, String subject, String status,
                     int attempts, String lastError, Instant createdAt, Instant sentAt) {
    }

    record EntryList(String object, List<EntryView> data) {
    }

    /** The signed-in person's own choices; the log is of the whole business and the gateway keeps it to owners and admins. */
    @GetMapping("/v1/notification_preferences")
    PreferenceList preferences(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return new PreferenceList("notification_preferences", email,
                notifications.preferences(jwt.getClaimAsString("mid"), email).stream()
                        .map(p -> new PreferenceView(p.template(), p.enabled())).toList());
    }

    @PutMapping("/v1/notification_preferences")
    PreferenceList update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdatePreference request) {
        if (!NotificationService.OPTIONAL.contains(request.template())) {
            throw ApiException.invalidRequest("unknown_template", "That email cannot be switched off", "template");
        }
        notifications.setPreference(jwt.getClaimAsString("mid"), jwt.getClaimAsString("email"), request.template(),
                request.enabled());
        return preferences(jwt);
    }

    @GetMapping("/v1/notifications")
    EntryList log(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) {
            throw ApiException.invalidRequest("invalid_limit", "limit must be between 1 and 100", "limit");
        }
        return new EntryList("list", notifications.log(jwt.getClaimAsString("mid"), limit).stream()
                .map((Entry e) -> new EntryView("notification", e.id(), e.recipient(), e.template(), e.subject(),
                        e.status(), e.attempts(), e.lastError(), e.createdAt(), e.sentAt()))
                .toList());
    }
}
