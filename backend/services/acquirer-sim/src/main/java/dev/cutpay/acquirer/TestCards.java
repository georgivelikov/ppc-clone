package dev.cutpay.acquirer;

import java.util.Map;
import java.util.Optional;

/** The documented test cards (docs/plans/phase-01-card-payments.md). Any other valid number is approved. */
final class TestCards {

    enum Behaviour { APPROVE, THREE_DS, DECLINE, PROCESSING_ERROR, TIMEOUT }

    record Outcome(Behaviour behaviour, String declineCode, String message) {
        static Outcome of(Behaviour behaviour) {
            return new Outcome(behaviour, null, null);
        }

        static Outcome decline(String code, String message) {
            return new Outcome(Behaviour.DECLINE, code, message);
        }
    }

    private static final Map<String, Outcome> BY_PAN = Map.of(
            "4000000000003220", Outcome.of(Behaviour.THREE_DS),
            "4000000000000002", Outcome.decline("card_declined", "Your card was declined."),
            "4000000000009995", Outcome.decline("insufficient_funds", "Your card has insufficient funds."),
            "4000000000000069", Outcome.decline("expired_card", "Your card has expired."),
            "4000000000000119", Outcome.of(Behaviour.PROCESSING_ERROR),
            "4000000000007775", Outcome.of(Behaviour.TIMEOUT));

    private TestCards() {
    }

    static Outcome forPan(String pan) {
        return Optional.ofNullable(BY_PAN.get(pan)).orElse(Outcome.of(Behaviour.APPROVE));
    }

    static String brand(String pan) {
        if (pan.startsWith("4")) {
            return "visa";
        }
        if (pan.matches("^(5[1-5]|2[2-7]).*")) {
            return "mastercard";
        }
        if (pan.matches("^3[47].*")) {
            return "amex";
        }
        return "unknown";
    }

    static boolean luhnValid(String pan) {
        int sum = 0;
        boolean alternate = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int digit = pan.charAt(i) - '0';
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}
