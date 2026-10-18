package dev.cutpay.ledger.config;

/** Topic names the ledger reads and writes. Producers own their topics (see each service's KafkaTopicsConfig). */
public final class Topics {

    public static final String MERCHANT = "merchant.events";
    public static final String PAYMENT = "payment.events";
    public static final String REFUND = "refund.events";
    public static final String PAYOUT = "payout.events";
    public static final String BNPL = "bnpl.events";
    public static final String LEDGER_ENTRIES = "ledger.entries";

    private Topics() {
    }
}
