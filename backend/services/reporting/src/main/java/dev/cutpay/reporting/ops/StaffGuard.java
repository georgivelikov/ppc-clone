package dev.cutpay.reporting.ops;

import dev.cutpay.web.ApiException;
import org.springframework.security.oauth2.jwt.Jwt;

final class StaffGuard {

    private StaffGuard() {
    }

    static void staff(Jwt jwt) {
        if (!"staff".equals(jwt.getClaimAsString("realm"))) {
            throw ApiException.forbidden("staff_only", "This is for Cutpay staff");
        }
    }

    static void admin(Jwt jwt) {
        staff(jwt);
        if (!"staff_admin".equals(jwt.getClaimAsString("role"))) {
            throw ApiException.forbidden("staff_admin_only", "Only back-office admins can do this");
        }
    }
}
