package dev.cutpay.identity.staff;

import dev.cutpay.identity.security.JwtService;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cutpay's own people: signing in, and what each role may do. */
@Service
public class StaffService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);

    public record Session(String accessToken, long expiresIn, String id, String email, String fullName, String role) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final boolean seed;
    private final String seedPassword;
    /** Verified against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    public StaffService(JdbcClient jdbc, PasswordEncoder passwordEncoder, JwtService jwt, @Value("${cutpay.staff.seed:false}") boolean seed,
                        @Value("${cutpay.staff.seed-password:}") String seedPassword) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.seed = seed;
        this.seedPassword = seedPassword;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seed || seedPassword.length() < 10) {
            return;
        }
        if (jdbc.sql("SELECT count(*) FROM staff_users").query(Integer.class).single() > 0) {
            return;
        }
        add("admin@cutpay.test", "Alex Admin", "admin");
        add("admin2@cutpay.test", "Sam Second-Admin", "admin");
        add("reviewer@cutpay.test", "Rita Reviewer", "reviewer");
        log.info("Created the local back-office sign-ins (password from cutpay.staff.seed-password)");
    }

    private void add(String email, String name, String role) {
        jdbc.sql("INSERT INTO staff_users (id, email, password_hash, full_name, role) VALUES (:id, :e, :h, :n, :r)")
                .param("id", Ids.generate("stf")).param("e", email).param("h", passwordEncoder.encode(seedPassword))
                .param("n", name).param("r", role).update();
    }

    @Transactional
    public Session login(String email, String password) {
        var row = jdbc.sql("SELECT id, password_hash, full_name, role, email FROM staff_users WHERE lower(email) = lower(:e)")
                .param("e", email.trim())
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)})
                .optional();
        boolean ok = passwordEncoder.matches(password, row.map(r -> r[1]).orElse(dummyHash));
        if (row.isEmpty() || !ok) {
            throw ApiException.unauthorized("invalid_credentials", "Incorrect email or password");
        }
        String[] r = row.get();
        jdbc.sql("UPDATE staff_users SET last_login_at = now() WHERE id = :id").param("id", r[0]).update();
        var token = jwt.issueForStaff(r[0], r[4], r[3]);
        return new Session(token.value(), token.expiresInSeconds(), r[0], r[4], r[2], r[3]);
    }

    /** Anyone from the back-office. */
    public static void requireStaff(Jwt jwt) {
        String role = jwt.getClaimAsString("role");
        if (role == null || !role.startsWith("staff_") || !"staff".equals(jwt.getClaimAsString("realm"))) {
            throw ApiException.forbidden("staff_only", "This is for Cutpay staff");
        }
    }

    /** Staff with the admin role: money and payouts. Reviewers read and review. */
    public static void requireAdmin(Jwt jwt) {
        requireStaff(jwt);
        if (!"staff_admin".equals(jwt.getClaimAsString("role"))) {
            throw ApiException.forbidden("staff_admin_only", "Only back-office admins can do this");
        }
    }
}
