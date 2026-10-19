package dev.cutpay.identity.security;

import dev.cutpay.identity.audit.AuditLog;
import dev.cutpay.identity.domain.Repositories.UserRepository;
import dev.cutpay.identity.domain.User;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The parts of signing in that are not "email and password": confirming the email address, resetting a forgotten
 * password, two-factor codes with recovery codes, changing the password, and the list of places you are signed in.
 */
@Service
public class SecurityService {

    static final Duration RESET_TTL = Duration.ofHours(1);
    static final Duration VERIFY_TTL = Duration.ofDays(2);
    private static final int RECOVERY_CODES = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Mailer mailer;
    private final AuditLog audit;

    public SecurityService(JdbcClient jdbc, UserRepository users, PasswordEncoder passwordEncoder, Mailer mailer,
                           AuditLog audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.audit = audit;
    }

    // --- one-time links ---------------------------------------------------------------------------------------

    /** Sends the "confirm your address" email. */
    @Transactional
    public void sendVerification(User user, String merchantId) {
        String token = issueToken(user.getId(), "email_verification", VERIFY_TTL);
        mailer.send(merchantId, user.getEmail(), "email_verification",
                Map.of("verify_url", mailer.dashboardUrl() + "/verify-email?token=" + token));
    }

    @Transactional
    public void verifyEmail(String token) {
        String userId = consumeToken(token, "email_verification");
        users.findById(userId).orElseThrow().markEmailVerified();
    }

    /** Always looks the same from outside, so the form cannot be used to find out who has an account. */
    @Transactional
    public void requestPasswordReset(String email) {
        users.findByEmail(email.trim()).ifPresent(user -> {
            String token = issueToken(user.getId(), "password_reset", RESET_TTL);
            mailer.send(null, user.getEmail(), "password_reset",
                    Map.of("reset_url", mailer.dashboardUrl() + "/reset-password?token=" + token));
        });
    }

    /** Sets the new password and signs the person out everywhere: whoever had the old one is out. */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        requireStrong(newPassword);
        String userId = consumeToken(token, "password_reset");
        User user = users.findById(userId).orElseThrow();
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        endAllSessions(userId);
    }

    @Transactional
    public void changePassword(String userId, String merchantId, String current, String next, String exceptSession) {
        User user = users.findById(userId).orElseThrow();
        if (!passwordEncoder.matches(current, user.getPasswordHash())) {
            throw ApiException.forbidden("invalid_credentials", "The current password is not right");
        }
        requireStrong(next);
        user.changePasswordHash(passwordEncoder.encode(next));
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL AND family_id <> :keep")
                .param("u", userId).param("keep", exceptSession == null ? "" : exceptSession).update();
        audit.record(merchantId, userId, user.getEmail(), "security.password_changed", "user", userId, null);
    }

    private String issueToken(String userId, String purpose, Duration ttl) {
        users.flush(); // a user saved a moment ago must exist for the SQL below
        jdbc.sql("UPDATE one_time_tokens SET used_at = now() WHERE user_id = :u AND purpose = :p AND used_at IS NULL")
                .param("u", userId).param("p", purpose).update();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("INSERT INTO one_time_tokens (id, user_id, purpose, token_hash, expires_at) VALUES (:id, :u, :p, :h, :e)")
                .param("id", Ids.generate("ott")).param("u", userId).param("p", purpose).param("h", hash(token))
                .param("e", Timestamp.from(Instant.now().plus(ttl))).update();
        return token;
    }

    private String consumeToken(String token, String purpose) {
        return jdbc.sql("""
                        UPDATE one_time_tokens SET used_at = now()
                        WHERE token_hash = :h AND purpose = :p AND used_at IS NULL AND expires_at > now()
                        RETURNING user_id
                        """)
                .param("h", hash(token)).param("p", purpose)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.invalidRequest("invalid_token",
                        "This link is not valid any more. Ask for a new one.", "token"));
    }

    // --- two-factor codes -------------------------------------------------------------------------------------

    public record TotpSetup(String secret, String otpauthUri) {
    }

    /** Starts setup. Nothing changes at sign-in until the first code is confirmed. */
    @Transactional
    public TotpSetup beginTotp(String userId) {
        User user = users.findById(userId).orElseThrow();
        if (user.isTotpEnabled()) {
            throw ApiException.conflict("totp_already_enabled", "Two-factor sign-in is already on", null);
        }
        String secret = Totp.newSecret();
        user.beginTotp(secret);
        return new TotpSetup(secret, Totp.uri("Cutpay", user.getEmail(), secret));
    }

    /** Confirms the first code, turns two-factor on and returns the recovery codes, which are shown only now. */
    @Transactional
    public List<String> enableTotp(String userId, String merchantId, String code) {
        User user = users.findById(userId).orElseThrow();
        if (user.getTotpSecret() == null || user.isTotpEnabled()) {
            throw ApiException.conflict("totp_not_started", "Start the setup first", null);
        }
        if (!Totp.verify(user.getTotpSecret(), code, Instant.now())) {
            throw ApiException.invalidRequest("invalid_code", "That code is not right. Check the time on your phone.", "code");
        }
        user.enableTotp();
        List<String> codes = new ArrayList<>();
        jdbc.sql("DELETE FROM recovery_codes WHERE user_id = :u").param("u", userId).update();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String recovery = recoveryCode();
            codes.add(recovery);
            jdbc.sql("INSERT INTO recovery_codes (user_id, code_hash) VALUES (:u, :h)")
                    .param("u", userId).param("h", hash(recovery.replace("-", ""))).update();
        }
        audit.record(merchantId, userId, user.getEmail(), "security.totp_enabled", "user", userId, null);
        return codes;
    }

    @Transactional
    public void disableTotp(String userId, String merchantId, String password, String code) {
        User user = users.findById(userId).orElseThrow();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.forbidden("invalid_credentials", "The password is not right");
        }
        requireSecondFactor(user, code);
        user.disableTotp();
        jdbc.sql("DELETE FROM recovery_codes WHERE user_id = :u").param("u", userId).update();
        audit.record(merchantId, userId, user.getEmail(), "security.totp_disabled", "user", userId, null);
    }

    /** At sign-in: a six-digit code from the app, or one of the recovery codes (each works once). */
    @Transactional
    public void requireSecondFactor(User user, String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.unauthorized("totp_required", "Enter the code from your authenticator app");
        }
        String trimmed = code.trim();
        if (Totp.verify(user.getTotpSecret(), trimmed, Instant.now())) {
            return;
        }
        int used = jdbc.sql("""
                        UPDATE recovery_codes SET used_at = now()
                        WHERE user_id = :u AND code_hash = :h AND used_at IS NULL
                        """)
                .param("u", user.getId()).param("h", hash(trimmed.replace("-", "").toLowerCase())).update();
        if (used == 0) {
            throw ApiException.unauthorized("invalid_totp", "That code is not right");
        }
    }

    public boolean isLiveReady(String userId) {
        return users.findById(userId).map(User::isTotpEnabled).orElse(false);
    }

    private static String recoveryCode() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // --- overview and sessions --------------------------------------------------------------------------------

    public record Overview(boolean emailVerified, boolean totpEnabled, int recoveryCodesLeft) {
    }

    @Transactional(readOnly = true)
    public Overview overview(String userId) {
        User user = users.findById(userId).orElseThrow();
        int left = jdbc.sql("SELECT count(*) FROM recovery_codes WHERE user_id = :u AND used_at IS NULL")
                .param("u", userId).query(Integer.class).single();
        return new Overview(user.getEmailVerifiedAt() != null, user.isTotpEnabled(), left);
    }

    public record SessionRow(String id, String userAgent, Instant startedAt, Instant lastActive, boolean current) {
    }

    /** One row per session: the live token of each family. */
    @Transactional(readOnly = true)
    public List<SessionRow> sessions(String userId, String merchantId, String currentSession) {
        return jdbc.sql("""
                        SELECT family_id, user_agent, created_at,
                               (SELECT min(created_at) FROM refresh_tokens f WHERE f.family_id = t.family_id) AS started
                        FROM refresh_tokens t
                        WHERE user_id = :u AND merchant_id = :m AND revoked_at IS NULL AND expires_at > now()
                        ORDER BY created_at DESC
                        """)
                .param("u", userId).param("m", merchantId)
                .query((rs, n) -> new SessionRow(rs.getString("family_id"), rs.getString("user_agent"),
                        rs.getTimestamp("started").toInstant(), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("family_id").equals(currentSession)))
                .list();
    }

    @Transactional
    public void endSession(String userId, String merchantId, String sessionId) {
        int changed = jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :u AND family_id = :f AND revoked_at IS NULL")
                .param("u", userId).param("f", sessionId).update();
        if (changed == 0) {
            throw ApiException.notFound("session_not_found", "No such session");
        }
        audit.record(merchantId, userId, null, "security.session_ended", "session", sessionId, null);
    }

    private void endAllSessions(String userId) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL")
                .param("u", userId).update();
    }

    private static void requireStrong(String password) {
        if (password == null || password.length() < 10) {
            throw ApiException.invalidRequest("weak_password", "Password must be at least 10 characters", "password");
        }
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
