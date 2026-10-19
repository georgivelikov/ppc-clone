package dev.cutpay.identity.onboarding;

import java.math.BigInteger;

/** The check digits of an IBAN (ISO 13616): move the first four characters to the end, turn letters into numbers, and the rest by 97 is 1. */
final class Iban {

    private Iban() {
    }

    static boolean isValid(String iban) {
        if (iban == null || !iban.matches("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
