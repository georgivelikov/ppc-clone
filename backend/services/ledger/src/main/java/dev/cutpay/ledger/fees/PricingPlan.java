package dev.cutpay.ledger.fees;

/** A merchant's pricing: the fee strategy plus how long funds stay pending before they become available. */
public record PricingPlan(String merchantId, Rate eeaConsumer, Rate other, int delayDays) {

    /** Mimics Paypercut: 1.29% + 0.10 for EEA consumer cards, 2.69% + 0.10 otherwise, funds ready in 2 days. */
    public static PricingPlan standard(String merchantId) {
        return new PricingPlan(merchantId, new Rate(129, 10), new Rate(269, 10), 2);
    }

    public FeeStrategy strategy() {
        return new TieredCardFee(eeaConsumer, other);
    }
}
