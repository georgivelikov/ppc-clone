package dev.cutpay.notifications.config;

public final class Topics {

    /** Commands: any service may ask for an email here. */
    public static final String NOTIFICATION = "notification.events";
    public static final String MERCHANT = "merchant.events";
    public static final String PAYMENT = "payment.events";
    public static final String PAYOUT = "payout.events";
    public static final String DISPUTE = "dispute.events";
    public static final String ONBOARDING = "onboarding.events";
    public static final String REPORT = "report.events";

    private Topics() {
    }
}
