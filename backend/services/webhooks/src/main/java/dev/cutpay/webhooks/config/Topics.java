package dev.cutpay.webhooks.config;

public final class Topics {

    public static final String PAYMENT = "payment.events";
    public static final String REFUND = "refund.events";
    public static final String PAYOUT = "payout.events";
    public static final String LINK = "link.events";
    public static final String CUSTOMER = "customer.events";
    public static final String SUBSCRIPTION = "subscription.events";
    public static final String INVOICE = "invoice.events";
    public static final String API_REQUESTS = "api.requests";

    private Topics() {
    }
}
