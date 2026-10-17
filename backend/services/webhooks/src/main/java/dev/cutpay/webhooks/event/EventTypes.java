package dev.cutpay.webhooks.event;

import java.util.List;
import java.util.Set;

/** The public event types and the rules for which ones an endpoint is subscribed to. */
public final class EventTypes {

    public static final String TEST = "webhook_endpoint.test";

    public static final Set<String> ALL = Set.of(
            "payment_intent.created", "payment_intent.requires_action", "payment_intent.succeeded",
            "payment_intent.payment_failed", "payment_intent.canceled",
            "refund.created", "charge.refunded", "refund.failed",
            "payout.created", "payout.paid", "payout.failed",
            TEST);

    private EventTypes() {
    }

    /** True for {@code "*"}, an exact type, or a {@code "family.*"} wildcard such as {@code "payout.*"}. */
    public static boolean isValidSubscription(String entry) {
        if (entry.equals("*") || ALL.contains(entry)) {
            return true;
        }
        return entry.endsWith(".*") && ALL.stream().anyMatch(t -> t.startsWith(prefix(entry)));
    }

    public static boolean matches(List<String> subscriptions, String type) {
        for (String entry : subscriptions) {
            if (entry.equals("*") || entry.equals(type) || (entry.endsWith(".*") && type.startsWith(prefix(entry)))) {
                return true;
            }
        }
        return false;
    }

    /** {@code "payout.*"} becomes {@code "payout."}. */
    private static String prefix(String wildcard) {
        return wildcard.substring(0, wildcard.length() - 1);
    }
}
