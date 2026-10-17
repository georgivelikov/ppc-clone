package dev.cutpay.webhooks.config;

import dev.cutpay.web.ApiException;
import dev.cutpay.web.RequestMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling and which mode they work in. An API key carries its mode in the token and that always wins
 * over the {@code livemode} query parameter; dashboard sessions have no mode claim and use the parameter.
 */
public record Caller(String merchantId, Boolean claimedMode, boolean requestedMode, String role) {

    private static final Set<String> CAN_WRITE = Set.of("owner", "admin", "developer", "api");

    public static Caller of(Jwt jwt, boolean requestedMode) {
        return new Caller(jwt.getClaimAsString("mid"), jwt.getClaimAsBoolean("livemode"), requestedMode,
                jwt.getClaimAsString("role"));
    }

    /** The mode for creating and listing. */
    public boolean livemode() {
        return RequestMode.resolve(claimedMode, requestedMode);
    }

    public void requireWrite() {
        if (!CAN_WRITE.contains(role)) {
            throw ApiException.forbidden("insufficient_role", "Your role cannot change webhook settings");
        }
    }

    /** For lookups by id: keys only see their own mode, dashboard sessions see both. */
    public String modeClause(String column) {
        return claimedMode == null ? "" : " AND " + column + " = :mode";
    }

    public Map<String, Object> params() {
        Map<String, Object> params = new HashMap<>();
        params.put("merchant", merchantId);
        if (claimedMode != null) {
            params.put("mode", claimedMode);
        }
        return params;
    }
}
