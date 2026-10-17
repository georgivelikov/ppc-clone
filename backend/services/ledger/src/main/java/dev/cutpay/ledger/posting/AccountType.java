package dev.cutpay.ledger.posting;

import java.util.Locale;

public enum AccountType {
    MERCHANT_PENDING,
    MERCHANT_AVAILABLE,
    MERCHANT_RESERVE,
    PLATFORM_FEES,
    PLATFORM_CASH,
    PAYOUTS_IN_TRANSIT,
    CUSTOMER_FUNDS;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
