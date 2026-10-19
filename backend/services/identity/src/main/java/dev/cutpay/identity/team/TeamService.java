package dev.cutpay.identity.team;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.identity.audit.AuditLog;
import dev.cutpay.identity.domain.Membership;
import dev.cutpay.identity.domain.Repositories.MembershipRepository;
import dev.cutpay.identity.domain.Repositories.UserRepository;
import dev.cutpay.identity.domain.User;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

    static final Duration INVITE_TTL = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final AuditLog audit;
    private final OutboxWriter outbox;

    public TeamService(JdbcClient jdbc, UserRepository users, MembershipRepository memberships,
                       PasswordEncoder passwordEncoder, AuditLog audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.users = users;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record Member(String userId, String email, String fullName, String role, Instant joinedAt,
                         Instant lastLoginAt) {
    }

    public record Invite(String id, String email, String role, String invitedBy, Instant createdAt,
                         Instant expiresAt, String status) {
    }

    public record Created(Invite invite, String token) {
    }

    public record Preview(String merchantName, String email, String role, boolean accountExists) {
    }

    public record Accepted(String userId, String merchantId) {
    }

    @Transactional(readOnly = true)
    public List<Member> members(String merchantId) {
        return jdbc.sql("""
                        SELECT u.id, u.email, u.full_name, m.role, m.created_at, u.last_login_at
                        FROM memberships m JOIN users u ON u.id = m.user_id
                        WHERE m.merchant_id = :merchant ORDER BY m.created_at, u.id
                        """)
                .param("merchant", merchantId)
                .query((rs, n) -> new Member(rs.getString("id"), rs.getString("email"), rs.getString("full_name"),
                        rs.getString("role").toLowerCase(Locale.ROOT), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant()))
                .list();
    }

    @Transactional(readOnly = true)
    public List<Invite> invites(String merchantId) {
        return jdbc.sql("""
                        SELECT i.id, i.email, i.role, u.email AS invited_by, i.created_at, i.expires_at,
                               i.accepted_at, i.revoked_at
                        FROM invitations i JOIN users u ON u.id = i.invited_by
                        WHERE i.merchant_id = :merchant ORDER BY i.created_at DESC LIMIT 100
                        """)
                .param("merchant", merchantId)
                .query((rs, n) -> {
                    Instant expires = rs.getTimestamp("expires_at").toInstant();
                    String status = rs.getTimestamp("accepted_at") != null ? "accepted"
                            : rs.getTimestamp("revoked_at") != null ? "revoked"
                            : expires.isBefore(Instant.now()) ? "expired" : "pending";
                    return new Invite(rs.getString("id"), rs.getString("email"), rs.getString("role"),
                            rs.getString("invited_by"), rs.getTimestamp("created_at").toInstant(), expires, status);
                })
                .list();
    }

    @Transactional
    public Created invite(Jwt actor, String email, String role) {
        String merchantId = actor.getClaimAsString("mid");
        String actorRole = actor.getClaimAsString("role");
        String wanted = role.toLowerCase(Locale.ROOT);
        if (!TeamRules.ASSIGNABLE.contains(wanted)) {
            throw ApiException.invalidRequest("invalid_role", "Role must be admin, developer, viewer or support", "role");
        }
        if (!TeamRules.canGrant(actorRole, wanted)) {
            throw ApiException.forbidden("insufficient_role", "Your role cannot invite someone as " + wanted);
        }
        String address = email.trim();
        boolean alreadyMember = users.findByEmail(address)
                .flatMap(u -> memberships.findByUserIdAndMerchantId(u.getId(), merchantId)).isPresent();
        if (alreadyMember) {
            throw ApiException.conflict("already_member", "This person is already in the team", "email");
        }
        // A new invitation replaces an unanswered one for the same address.
        jdbc.sql("""
                        UPDATE invitations SET revoked_at = now()
                        WHERE merchant_id = :merchant AND lower(email) = lower(:email)
                          AND accepted_at IS NULL AND revoked_at IS NULL
                        """)
                .param("merchant", merchantId).param("email", address).update();

        String token = newToken();
        String id = Ids.generate("inv");
        Instant now = Instant.now();
        jdbc.sql("""
                        INSERT INTO invitations (id, merchant_id, email, role, token_hash, invited_by, created_at, expires_at)
                        VALUES (:id, :merchant, :email, :role, :hash, :by, :now, :expires)
                        """)
                .param("id", id).param("merchant", merchantId).param("email", address).param("role", wanted)
                .param("hash", hash(token)).param("by", actor.getSubject())
                .param("now", java.sql.Timestamp.from(now)).param("expires", java.sql.Timestamp.from(now.plus(INVITE_TTL)))
                .update();
        audit.record(merchantId, actor, "team.invite_sent", "invitation", id, address + " as " + wanted);
        return new Created(new Invite(id, address, wanted, actor.getClaimAsString("email"), now,
                now.plus(INVITE_TTL), "pending"), token);
    }

    @Transactional
    public void revokeInvite(Jwt actor, String inviteId) {
        String merchantId = actor.getClaimAsString("mid");
        int changed = jdbc.sql("""
                        UPDATE invitations SET revoked_at = now()
                        WHERE id = :id AND merchant_id = :merchant AND accepted_at IS NULL AND revoked_at IS NULL
                        """)
                .param("id", inviteId).param("merchant", merchantId).update();
        if (changed == 0) {
            throw ApiException.notFound("invite_not_found", "No open invitation with that id");
        }
        audit.record(merchantId, actor, "team.invite_revoked", "invitation", inviteId, null);
    }

    @Transactional
    public Member changeRole(Jwt actor, String userId, String role) {
        String merchantId = actor.getClaimAsString("mid");
        String wanted = role.toLowerCase(Locale.ROOT);
        if (userId.equals(actor.getSubject())) {
            throw ApiException.forbidden("cannot_change_self", "You cannot change your own role");
        }
        Membership target = memberships.findByUserIdAndMerchantId(userId, merchantId)
                .orElseThrow(() -> ApiException.notFound("member_not_found", "No such team member"));
        String current = target.getRole().name().toLowerCase(Locale.ROOT);
        if (!TeamRules.ASSIGNABLE.contains(wanted)) {
            throw ApiException.invalidRequest("invalid_role", "Role must be admin, developer, viewer or support", "role");
        }
        String actorRole = actor.getClaimAsString("role");
        if (!TeamRules.canManage(actorRole, current) || !TeamRules.canGrant(actorRole, wanted)) {
            throw ApiException.forbidden("insufficient_role", "Your role cannot make this change");
        }
        target.setRole(Membership.Role.valueOf(wanted.toUpperCase(Locale.ROOT)));
        memberships.saveAndFlush(target); // the member list below reads with SQL
        // Tokens already issued still carry the old role until they expire; end the sessions so it takes effect.
        endSessions(userId, merchantId);
        audit.record(merchantId, actor, "team.role_changed", "user", userId, current + " to " + wanted);
        return members(merchantId).stream().filter(m -> m.userId().equals(userId)).findFirst().orElseThrow();
    }

    @Transactional
    public void remove(Jwt actor, String userId) {
        String merchantId = actor.getClaimAsString("mid");
        if (userId.equals(actor.getSubject())) {
            throw ApiException.forbidden("cannot_remove_self", "You cannot remove yourself");
        }
        Membership target = memberships.findByUserIdAndMerchantId(userId, merchantId)
                .orElseThrow(() -> ApiException.notFound("member_not_found", "No such team member"));
        String current = target.getRole().name().toLowerCase(Locale.ROOT);
        if (!TeamRules.canManage(actor.getClaimAsString("role"), current)) {
            throw ApiException.forbidden("insufficient_role", "Your role cannot remove this member");
        }
        memberships.delete(target);
        endSessions(userId, merchantId);
        audit.record(merchantId, actor, "team.member_removed", "user", userId, current);
    }

    @Transactional(readOnly = true)
    public Preview preview(String token) {
        Map<String, Object> row = open(token);
        String email = (String) row.get("email");
        return new Preview((String) row.get("display_name"), email, (String) row.get("role"),
                users.findByEmail(email).isPresent());
    }

    /** Joins the team. A person who already has an account proves it with their password; others make one. */
    @Transactional
    public Accepted accept(String token, String fullName, String password) {
        Map<String, Object> row = open(token);
        String merchantId = (String) row.get("merchant_id");
        String email = (String) row.get("email");
        Optional<User> existing = users.findByEmail(email);
        User user;
        if (existing.isPresent()) {
            user = existing.get();
            if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
                throw ApiException.unauthorized("invalid_credentials", "Incorrect password for " + email);
            }
        } else {
            if (fullName == null || fullName.isBlank()) {
                throw ApiException.invalidRequest("name_required", "Tell us your name", "full_name");
            }
            if (password == null || password.length() < 10) {
                throw ApiException.invalidRequest("weak_password", "Password must be at least 10 characters",
                        "password");
            }
            user = users.save(new User(Ids.generate("usr"), email, passwordEncoder.encode(password), fullName.trim()));
        }
        if (memberships.findByUserIdAndMerchantId(user.getId(), merchantId).isPresent()) {
            throw ApiException.conflict("already_member", "You are already in this team", null);
        }
        memberships.save(new Membership(Ids.generate("mbr"), user.getId(), merchantId,
                Membership.Role.valueOf(((String) row.get("role")).toUpperCase(Locale.ROOT))));
        jdbc.sql("UPDATE invitations SET accepted_at = now() WHERE id = :id").param("id", row.get("id")).update();
        audit.record(merchantId, user.getId(), user.getEmail(), "team.invite_accepted", "invitation",
                (String) row.get("id"), (String) row.get("role"));
        outbox.write("merchant.events", "TeamMemberJoined", merchantId, merchantId, null,
                Map.of("merchant_id", merchantId, "user_id", user.getId(), "role", row.get("role")));
        return new Accepted(user.getId(), merchantId);
    }

    private Map<String, Object> open(String token) {
        return jdbc.sql("""
                        SELECT i.id, i.merchant_id, i.email, i.role, m.display_name
                        FROM invitations i JOIN merchants m ON m.id = i.merchant_id
                        WHERE i.token_hash = :hash AND i.accepted_at IS NULL AND i.revoked_at IS NULL
                          AND i.expires_at > now()
                        """)
                .param("hash", hash(token))
                .query((rs, n) -> Map.<String, Object>of("id", rs.getString("id"),
                        "merchant_id", rs.getString("merchant_id"), "email", rs.getString("email"),
                        "role", rs.getString("role"), "display_name", rs.getString("display_name")))
                .optional()
                .orElseThrow(() -> ApiException.notFound("invite_not_found",
                        "This invitation is not valid any more. Ask for a new one."));
    }

    private void endSessions(String userId, String merchantId) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :u AND merchant_id = :m AND revoked_at IS NULL")
                .param("u", userId).param("m", merchantId).update();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "inv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
