package dev.cutpay.bnpl.routing;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** How a merchant wants the provider picked. */
public enum RoutingMode {
    /** Offers are shown in the merchant's priority order and the customer chooses. */
    CUSTOMER_CHOICE,
    /** The provider that keeps the least. */
    CHEAPEST,
    /** The provider that decides soonest. */
    FASTEST,
    /** The provider most likely to say yes. */
    BEST_APPROVAL,
    /** A mix of the three, by the merchant's weights. */
    WEIGHTED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<RoutingMode> fromWire(String value) {
        return Arrays.stream(values()).filter(mode -> mode.wire().equals(value)).findFirst();
    }
}
