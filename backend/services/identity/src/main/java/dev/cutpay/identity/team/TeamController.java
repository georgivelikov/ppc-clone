package dev.cutpay.identity.team;

import dev.cutpay.identity.audit.AuditLog;
import dev.cutpay.identity.team.TeamService.Created;
import dev.cutpay.identity.team.TeamService.Invite;
import dev.cutpay.identity.team.TeamService.Member;
import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The team of one business. The gateway only lets owners and admins in; the rules of who may do what live in {@link TeamRules}. */
@RestController
public class TeamController {

    private final TeamService team;
    private final AuditLog audit;

    public TeamController(TeamService team, AuditLog audit) {
        this.team = team;
        this.audit = audit;
    }

    public record MemberView(String object, String userId, String email, String fullName, String role,
                             java.time.Instant joinedAt, java.time.Instant lastLoginAt) {

        static MemberView of(Member m) {
            return new MemberView("team_member", m.userId(), m.email(), m.fullName(), m.role(), m.joinedAt(),
                    m.lastLoginAt());
        }
    }

    public record InviteView(String object, String id, String email, String role, String invitedBy,
                             java.time.Instant createdAt, java.time.Instant expiresAt, String status,
                             String acceptPath) {

        static InviteView of(Invite i, String token) {
            return new InviteView("invitation", i.id(), i.email(), i.role(), i.invitedBy(), i.createdAt(),
                    i.expiresAt(), i.status(), token == null ? null : "/accept-invite?token=" + token);
        }
    }

    public record ListOf<T>(String object, List<T> data) {
    }

    public record InviteRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank String role) {
    }

    public record RoleRequest(@NotBlank String role) {
    }

    public record AuditView(String object, long id, String actorId, String actorEmail, String action,
                            String targetType, String targetId, String detail, java.time.Instant createdAt) {
    }

    @GetMapping("/v1/team/members")
    ListOf<MemberView> members(@AuthenticationPrincipal Jwt jwt) {
        return new ListOf<>("list", team.members(merchant(jwt)).stream().map(MemberView::of).toList());
    }

    @PatchMapping("/v1/team/members/{userId}")
    MemberView changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId,
                          @Valid @RequestBody RoleRequest request) {
        return MemberView.of(team.changeRole(jwt, userId, request.role()));
    }

    @DeleteMapping("/v1/team/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId) {
        team.remove(jwt, userId);
    }

    @GetMapping("/v1/team/invites")
    ListOf<InviteView> invites(@AuthenticationPrincipal Jwt jwt) {
        return new ListOf<>("list", team.invites(merchant(jwt)).stream().map(i -> InviteView.of(i, null)).toList());
    }

    /** The only response that carries the link. Email delivery comes with the notifications service. */
    @PostMapping("/v1/team/invites")
    @ResponseStatus(HttpStatus.CREATED)
    InviteView invite(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InviteRequest request) {
        Created created = team.invite(jwt, request.email(), request.role());
        return InviteView.of(created.invite(), created.token());
    }

    @DeleteMapping("/v1/team/invites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        team.revokeInvite(jwt, id);
    }

    @GetMapping("/v1/audit_events")
    @Transactional(readOnly = true)
    ListOf<AuditView> audit(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long before,
                            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) {
            throw ApiException.invalidRequest("invalid_limit", "limit must be between 1 and 100", "limit");
        }
        return new ListOf<>("list", audit.list(merchant(jwt), before, limit).stream()
                .map(e -> new AuditView("audit_event", e.id(), e.actorId(), e.actorEmail(), e.action(), e.targetType(),
                        e.targetId(), e.detail(), e.createdAt()))
                .toList());
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }
}
