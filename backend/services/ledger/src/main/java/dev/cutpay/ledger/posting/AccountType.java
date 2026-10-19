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
    BNPL_RECEIVABLE,
    /** The platform's side of currency conversions, one per currency: what it bought from merchants and sold to them. */
    PLATFORM_FX,
    /** Money taken from a merchant while a dispute is decided: it goes back to them or to the customer. */
    DISPUTES_HELD;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
