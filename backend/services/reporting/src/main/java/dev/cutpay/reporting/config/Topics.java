package dev.cutpay.reporting.config;

public final class Topics {

    public static final String PAYMENT = "payment.events";
    public static final String PAYOUT = "payout.events";
    public static final String DISPUTE = "dispute.events";
    public static final String LEDGER_ENTRIES = "ledger.entries";
    public static final String REPORT = "report.events";

    /** The topics the read model is built from, in no particular order. */
    public static final java.util.List<String> SOURCES = java.util.List.of(PAYMENT, PAYOUT, DISPUTE, LEDGER_ENTRIES);

    private Topics() {
    }
}
