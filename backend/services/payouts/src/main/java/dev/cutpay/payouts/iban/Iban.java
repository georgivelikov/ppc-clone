package dev.cutpay.payouts.iban;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** IBAN normalisation and validation (ISO 13616: structure plus the mod-97 checksum). */
public final class Iban {

    private static final Pattern SHAPE = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$");

    private Iban() {
    }

    /** Removes spaces and upper-cases; returns empty if the input is not a valid IBAN. */
    public static Optional<String> normalise(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String iban = input.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!SHAPE.matcher(iban).matches() || !checksumOk(iban)) {
            return Optional.empty();
        }
        return Optional.of(iban);
    }

    /** Move the first four characters to the end, turn letters into numbers (A=10 ... Z=35); the result mod 97 must be 1. */
    static boolean checksumOk(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            int value = Character.digit(rearranged.charAt(i), 36);
            // Letters expand to two digits; feeding them one digit at a time keeps the number small.
            remainder = value < 10 ? (remainder * 10 + value) % 97 : (remainder * 100 + value) % 97;
        }
        return remainder == 1;
    }

    /** What the merchant sees: the country and the last four characters. */
    public static String mask(String iban) {
        return iban.substring(0, 2) + "•• •••• " + iban.substring(iban.length() - 4);
    }

    public static String last4(String iban) {
        return iban.substring(iban.length() - 4);
    }
}
