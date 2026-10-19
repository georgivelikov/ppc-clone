package dev.cutpay.payments.payment;

import java.util.ArrayList;
import java.util.List;

/**
 * Which ways of paying a checkout offers for a payment. A rule per method, kept in one place so the checkout and the
 * server agree, and so a new method is one more line here. The instalments tab is decided by the BNPL service.
 */
public final class PaymentMethodRules {

    public static final String CARD = "card";
    public static final String APPLE_PAY = "apple_pay";
    public static final String GOOGLE_PAY = "google_pay";
    public static final String BLIK = "blik";

    private PaymentMethodRules() {
    }

    public static List<String> available(String currency, String captureMethod) {
        return available(currency, captureMethod, java.util.Set.of());
    }

    /** {@code disabled} are the methods the merchant switched off; a card cannot be. */
    public static List<String> available(String currency, String captureMethod, java.util.Set<String> disabled) {
        List<String> methods = new ArrayList<>(List.of(CARD, APPLE_PAY, GOOGLE_PAY));
        // BLIK is a Polish bank method: złoty only, and the money is taken when the customer confirms, so a payment that
        // is captured later cannot use it.
        if ("PLN".equals(currency) && !"manual".equals(captureMethod)) {
            methods.add(BLIK);
        }
        methods.removeIf(method -> !CARD.equals(method) && disabled.contains(method));
        return List.copyOf(methods);
    }
}
