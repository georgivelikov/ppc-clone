package dev.cutpay.money;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Currencies Cutpay accepts and the default currency for each supported merchant country. */
public final class SupportedCurrencies {

    public static final Set<String> CODES = Set.of("EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF");

    private static final Map<String, String> COUNTRY_DEFAULTS = Map.of(
            "BG", "EUR", // Bulgaria adopted the euro on 2026-01-01
            "GR", "EUR",
            "RO", "RON",
            "PL", "PLN",
            "HU", "HUF",
            "CZ", "CZK",
            "GB", "GBP",
            "DE", "EUR",
            "IE", "EUR");

    public static final Set<String> COUNTRIES = COUNTRY_DEFAULTS.keySet();

    private SupportedCurrencies() {
    }

    public static boolean isSupported(String code) {
        return code != null && CODES.contains(code.toUpperCase(Locale.ROOT));
    }

    public static boolean isSupportedCountry(String countryCode) {
        return countryCode != null && COUNTRY_DEFAULTS.containsKey(countryCode.toUpperCase(Locale.ROOT));
    }

    public static String defaultFor(String countryCode) {
        String currency = COUNTRY_DEFAULTS.get(countryCode.toUpperCase(Locale.ROOT));
        if (currency == null) {
            throw new IllegalArgumentException("Unsupported country: " + countryCode);
        }
        return currency;
    }
}
