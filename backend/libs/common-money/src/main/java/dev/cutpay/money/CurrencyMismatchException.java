package dev.cutpay.money;

import java.util.Currency;

public class CurrencyMismatchException extends IllegalArgumentException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Currency mismatch: %s vs %s".formatted(left, right));
    }
}
