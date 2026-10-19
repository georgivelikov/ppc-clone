package dev.cutpay.gateway.apikey;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;

/** What an API key may call. Pure functions, so every rule is unit-testable without a server. */
final class KeyPolicy {

    /** Routes that exist for signed-in team members only. No API key, whatever its type, may use them. */
    private static final List<String> TEAM_ONLY = List.of("/v1/api_keys", "/v1/merchant", "/v1/logs", "/v1/terminal", "/v1/qr", "/v1/team", "/v1/onboarding", "/v1/audit_events", "/v1/security", "/v1/notifications", "/v1/notification_preferences", "/me", "/auth");

    /** Paths where the browser-safe publishable key is accepted (see ADR 0009). */
    private static final List<String> PUBLISHABLE_PATHS = List.of("/checkout", "/vault", "/links", "/invoices");

    private static final Map<String, String> RESOURCE_BY_PATH = Map.ofEntries(
            Map.entry("/v1/payment_intents", "payments"),
            Map.entry("/v1/checkout_sessions", "payments"),
            Map.entry("/v1/payment_stats", "payments"),
            Map.entry("/v1/payment_method_settings", "payments"),
            Map.entry("/v1/payment_links", "payment_links"),
            Map.entry("/v1/customers", "customers"),
            Map.entry("/v1/connected_accounts", "connected_accounts"),
            Map.entry("/v1/products", "billing"),
            Map.entry("/v1/prices", "billing"),
            Map.entry("/v1/subscriptions", "billing"),
            Map.entry("/v1/subscription_stats", "billing"),
            Map.entry("/v1/invoices", "billing"),
            Map.entry("/v1/credit_balances", "billing"),
            Map.entry("/v1/bnpl", "bnpl"),
            Map.entry("/v1/billing_settings", "billing"),
            Map.entry("/v1/billing_notifications", "billing"),
            Map.entry("/v1/setup_intents", "customers"),
            Map.entry("/v1/refunds", "refunds"),
            Map.entry("/v1/disputes", "disputes"),
            Map.entry("/v1/reporting", "reporting"),
            Map.entry("/v1/balance", "balance"),
            Map.entry("/v1/balance_transactions", "balance"),
            Map.entry("/v1/balance_summary", "balance"),
            Map.entry("/v1/fx", "balance"),
            Map.entry("/v1/currency_settings", "balance"),
            Map.entry("/v1/payouts", "payouts"),
            Map.entry("/v1/payout_schedule", "payouts"),
            Map.entry("/v1/bank_accounts", "bank_accounts"),
            Map.entry("/v1/webhook_endpoints", "webhooks"),
            Map.entry("/v1/webhook_deliveries", "webhooks"),
            Map.entry("/v1/events", "webhooks"));

    private KeyPolicy() {
    }

    static boolean isTeamOnly(String path) {
        return TEAM_ONLY.stream().anyMatch(prefix -> matches(path, prefix));
    }

    static boolean isPublishableAllowed(String path) {
        return PUBLISHABLE_PATHS.stream().anyMatch(prefix -> matches(path, prefix));
    }

    /** A restricted key needs {@code resource:read} for reads, or {@code resource:write} (which implies read). */
    static boolean allows(List<String> scopes, HttpMethod method, String path) {
        if (scopes.contains("*")) {
            return true;
        }
        String resource = resourceOf(path);
        if (resource == null) {
            return false;
        }
        boolean read = HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
        return scopes.contains(resource + ":write") || (read && scopes.contains(resource + ":read"));
    }

    static String resourceOf(String path) {
        return RESOURCE_BY_PATH.entrySet().stream()
                .filter(e -> matches(path, e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    /** True for the prefix itself and anything below it; {@code /v1/payouts2} does not match {@code /v1/payouts}. */
    private static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
