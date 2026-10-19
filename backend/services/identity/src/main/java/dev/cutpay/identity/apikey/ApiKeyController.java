package dev.cutpay.identity.apikey;

import dev.cutpay.identity.apikey.ApiKeyService.Created;
import dev.cutpay.identity.audit.AuditLog;
import dev.cutpay.identity.security.SecurityService;
import dev.cutpay.identity.domain.ApiKey;
import dev.cutpay.identity.domain.ApiKey.Type;
import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Key management is for signed-in team members only; an API key can never mint or list API keys. */
@RestController
public class ApiKeyController {

    private static final Set<String> CAN_MANAGE = Set.of("owner", "admin", "developer");
    private static final long MAX_GRACE_HOURS = 24 * 7;

    private final ApiKeyService service;
    private final AuditLog audit;
    private final SecurityService security;

    public ApiKeyController(ApiKeyService service, AuditLog audit, SecurityService security) {
        this.security = security;
        this.service = service;
        this.audit = audit;
    }

    public record CreateApiKeyRequest(@NotBlank @Size(max = 80) String name, @NotNull String type,
                                      Boolean livemode, List<String> scopes) {
    }

    public record RollRequest(Integer graceHours) {
    }

    public record ApiKeyView(String id, String object, String name, String type, boolean livemode, String prefix,
                             String last4, List<String> scopes, String status, Instant createdAt,
                             Instant lastUsedAt, Instant expiresAt, Instant revokedAt, String secret) {

        static ApiKeyView of(ApiKey key, String secret) {
            Instant now = Instant.now();
            String status = key.getRevokedAt() != null ? "revoked"
                    : !key.isUsable(now) ? "expired"
                    : key.getExpiresAt() != null ? "expiring" : "active";
            return new ApiKeyView(key.getId(), "api_key", key.getName(), key.getType().name().toLowerCase(Locale.ROOT),
                    key.isLivemode(), key.getPrefix(), key.getLast4(), key.getScopes(), status, key.getCreatedAt(),
                    key.getLastUsedAt(), key.getExpiresAt(), key.getRevokedAt(), secret);
        }
    }

    public record ApiKeyList(String object, List<ApiKeyView> data) {
    }

    @GetMapping("/v1/api_keys")
    ApiKeyList list(@AuthenticationPrincipal Jwt jwt) {
        requireTeamMember(jwt);
        return new ApiKeyList("list",
                service.list(merchant(jwt)).stream().map(key -> ApiKeyView.of(key, null)).toList());
    }

    /** The only response that ever contains the secret. */
    @PostMapping("/v1/api_keys")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ApiKeyView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateApiKeyRequest request) {
        requireTeamMember(jwt);
        // Live keys move real money: owners and admins need two-factor sign-in before they can create one.
        if (Boolean.TRUE.equals(request.livemode()) && Set.of("owner", "admin").contains(jwt.getClaimAsString("role"))
                && !security.isLiveReady(jwt.getSubject())) {
            throw ApiException.forbidden("two_factor_required",
                    "Turn on two-factor sign-in (Settings, Security) before creating live API keys");
        }
        Type type;
        try {
            type = Type.valueOf(request.type().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("invalid_key_type", "Type must be secret, publishable or restricted",
                    "type");
        }
        Created created = service.create(merchant(jwt), jwt.getSubject(), request.name().trim(),
                Boolean.TRUE.equals(request.livemode()), type, request.scopes());
        audit.record(merchant(jwt), jwt, "api_key.created", "api_key", created.key().getId(),
                request.name().trim() + " (" + type.name().toLowerCase(Locale.ROOT) + ")");
        return ApiKeyView.of(created.key(), created.secret());
    }

    @PostMapping("/v1/api_keys/{id}/roll")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ApiKeyView roll(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                    @RequestBody(required = false) RollRequest request) {
        requireTeamMember(jwt);
        int hours = request == null || request.graceHours() == null ? 0 : request.graceHours();
        if (hours < 0 || hours > MAX_GRACE_HOURS) {
            throw ApiException.invalidRequest("invalid_grace_period",
                    "grace_hours must be between 0 and " + MAX_GRACE_HOURS, "grace_hours");
        }
        Created created = service.roll(merchant(jwt), id, jwt.getSubject(), Duration.ofHours(hours));
        audit.record(merchant(jwt), jwt, "api_key.rolled", "api_key", id, "new key " + created.key().getId());
        return ApiKeyView.of(created.key(), created.secret());
    }

    @DeleteMapping("/v1/api_keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        requireTeamMember(jwt);
        service.revoke(merchant(jwt), id);
        audit.record(merchant(jwt), jwt, "api_key.revoked", "api_key", id, null);
    }

    private static void requireTeamMember(Jwt jwt) {
        if (!CAN_MANAGE.contains(jwt.getClaimAsString("role"))) {
            throw ApiException.forbidden("insufficient_role", "Only owners, admins and developers can manage API keys");
        }
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }
}
