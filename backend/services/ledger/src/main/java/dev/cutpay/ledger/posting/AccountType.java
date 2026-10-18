package dev.cutpay.ledger.posting;

import java.util.Locale;

public enum AccountType {
    MERCHANT_PENDING,
    MERCHANT_AVAILABLE,
    MERCHANT_RESERVE,
    PLATFORM_FEES,
    PLATFORM_CASH,
    PAYOUTS_IN_TRANSIT,
    CUSTOMER_FUNDS,
    /** What buy-now-pay-later providers owe the platform for payments they approved and have not yet settled. */
    BNPL_RECEIVABLE;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
