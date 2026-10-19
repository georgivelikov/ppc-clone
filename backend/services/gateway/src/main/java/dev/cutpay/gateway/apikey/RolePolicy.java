package dev.cutpay.gateway.apikey;

import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;

/**
 * What a signed-in team member may call, by role (docs/adr/0025-team-roles-and-permissions.md).
 *
 * <p>This is the coarse check, done once at the edge by resource and by read or write. Services still make the
 * fine decisions (for instance, only an owner may hand out the owner role). Pure functions, so the whole matrix
 * is unit-tested without a server.
 */
final class RolePolicy {

    /** The role carried by tokens issued for API keys; their limits are the key's scopes, checked elsewhere. */
    static final String API = "api";

    private static final Set<String> EVERYTHING = Set.of("owner", "admin");

    /** Resources that exist only for team members, on top of those in {@link KeyPolicy}. */
    private static final Map<String, String> TEAM_RESOURCES = Map.ofEntries(
            Map.entry("/v1/api_keys", "api_keys"),
            Map.entry("/v1/merchant", "merchant"),
            Map.entry("/v1/logs", "logs"),
            Map.entry("/v1/team", "team"),
            Map.entry("/v1/audit_events", "team"),
            Map.entry("/v1/notifications", "team"),
            Map.entry("/v1/terminal", "payments"),
            Map.entry("/v1/qr", "payments"));

    /** Resources a role may read. Everything not listed is hidden from that role. */
    private static final Map<String, Set<String>> READS = Map.of(
            "developer", Set.of("payments", "payment_links", "customers", "connected_accounts", "billing", "bnpl",
                    "refunds", "disputes", "balance", "payouts", "bank_accounts", "webhooks", "api_keys", "logs", "merchant"),
            "viewer", Set.of("payments", "payment_links", "customers", "connected_accounts", "billing", "bnpl",
                    "refunds", "disputes", "balance", "payouts", "bank_accounts", "webhooks", "merchant"),
            "support", Set.of("payments", "payment_links", "customers", "billing", "bnpl", "refunds", "disputes", "merchant"));

    /** Resources a role may change. Writing implies reading, so each of these is also readable. */
    private static final Map<String, Set<String>> WRITES = Map.of(
            "developer", Set.of("payments", "payment_links", "customers", "connected_accounts", "billing", "bnpl",
                    "webhooks", "api_keys", "logs"),
            "viewer", Set.of(),
            "support", Set.of("refunds", "customers", "disputes"));

    private RolePolicy() {
    }

    /** Paths outside this policy (sign-in, the user's own profile) are open to every signed-in member. */
    static boolean allows(String role, HttpMethod method, String path) {
        if (role == null) {
            return true;
        }
        if (API.equals(role) || EVERYTHING.contains(role)) {
            return true;
        }
        // A person's own sign-in security and email choices are theirs whatever the role.
        if (path.equals("/me") || path.equals("/auth") || path.startsWith("/auth/") || path.equals("/v1/security")
                || path.startsWith("/v1/security/") || path.equals("/v1/notification_preferences")) {
            return true;
        }
        String resource = resourceOf(path);
        if (resource == null) {
            return isRead(method); // unknown routes: read-only for anyone but owners and admins
        }
        if (!READS.containsKey(role)) {
            return false; // an unknown role gets nothing
        }
        Set<String> writes = WRITES.get(role);
        if (writes.contains(resource)) {
            return true;
        }
        return isRead(method) && READS.get(role).contains(resource);
    }

    static String resourceOf(String path) {
        return TEAM_RESOURCES.entrySet().stream()
                .filter(e -> path.equals(e.getKey()) || path.startsWith(e.getKey() + "/"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> KeyPolicy.resourceOf(path));
    }

    private static boolean isRead(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
    }
}
