package dev.cutpay.identity.security;

import dev.cutpay.identity.domain.Repositories.UserRepository;
import dev.cutpay.identity.security.SecurityService.Overview;
import dev.cutpay.identity.security.SecurityService.SessionRow;
import dev.cutpay.identity.security.SecurityService.TotpSetup;
import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
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

/** A person's own sign-in security. Every role may use it, for themselves only. */
@RestController
public class SecurityController {

    private final SecurityService security;
    private final UserRepository users;

    public SecurityController(SecurityService security, UserRepository users) {
        this.security = security;
        this.users = users;
    }

    public record OverviewView(String object, boolean emailVerified, boolean totpEnabled, int recoveryCodesLeft) {

        static OverviewView of(Overview o) {
            return new OverviewView("security", o.emailVerified(), o.totpEnabled(), o.recoveryCodesLeft());
        }
    }

    public record SetupView(String secret, String otpauthUri) {
    }

    public record CodeRequest(@NotBlank String code) {
    }

    public record RecoveryCodes(List<String> recoveryCodes) {
    }

    public record DisableRequest(@NotBlank String password, @NotBlank String code) {
    }

    public record PasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record SessionView(String object, String id, String userAgent, Instant startedAt, Instant lastActive,
                              boolean current) {

        static SessionView of(SessionRow r) {
            return new SessionView("session", r.id(), r.userAgent(), r.startedAt(), r.lastActive(), r.current());
        }
    }

    public record SessionList(String object, List<SessionView> data) {
    }

    @GetMapping("/v1/security")
    OverviewView overview(@AuthenticationPrincipal Jwt jwt) {
        return OverviewView.of(security.overview(jwt.getSubject()));
    }

    @PostMapping("/v1/security/email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    void resendVerification(@AuthenticationPrincipal Jwt jwt) {
        var user = users.findById(jwt.getSubject()).orElseThrow();
        if (user.getEmailVerifiedAt() != null) {
            throw ApiException.conflict("already_verified", "Your email address is already confirmed", null);
        }
        security.sendVerification(user, jwt.getClaimAsString("mid"));
    }

    @PostMapping("/v1/security/totp/setup")
    SetupView setup(@AuthenticationPrincipal Jwt jwt) {
        TotpSetup setup = security.beginTotp(jwt.getSubject());
        return new SetupView(setup.secret(), setup.otpauthUri());
    }

    @PostMapping("/v1/security/totp/enable")
    @Transactional
    RecoveryCodes enable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest request) {
        return new RecoveryCodes(security.enableTotp(jwt.getSubject(), jwt.getClaimAsString("mid"), request.code()));
    }

    @PostMapping("/v1/security/totp/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DisableRequest request) {
        security.disableTotp(jwt.getSubject(), jwt.getClaimAsString("mid"), request.password(), request.code());
    }

    @PostMapping("/v1/security/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordRequest request) {
        security.changePassword(jwt.getSubject(), jwt.getClaimAsString("mid"), request.currentPassword(),
                request.newPassword(), jwt.getClaimAsString("sid"));
    }

    @GetMapping("/v1/security/sessions")
    SessionList sessions(@AuthenticationPrincipal Jwt jwt) {
        return new SessionList("list", security.sessions(jwt.getSubject(), jwt.getClaimAsString("mid"),
                jwt.getClaimAsString("sid")).stream().map(SessionView::of).toList());
    }

    @DeleteMapping("/v1/security/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void endSession(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        security.endSession(jwt.getSubject(), jwt.getClaimAsString("mid"), id);
    }
}
