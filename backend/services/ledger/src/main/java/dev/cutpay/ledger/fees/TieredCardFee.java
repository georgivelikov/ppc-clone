package dev.cutpay.ledger.fees;

/**
 * Cutpay's standard schedule: one rate for EEA consumer cards, another for everything else (business
 * cards and cards issued outside the EEA). A fee never exceeds the payment itself, so a tiny payment
 * nets zero rather than a negative amount.
 */
public record TieredCardFee(Rate eeaConsumer, Rate other) implements FeeStrategy {

    @Override
    public long feeFor(long amount, CardProfile card) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Cannot price a non-positive amount");
        }
        Rate rate = card.isEeaConsumer() ? eeaConsumer : other;
        return Math.min(rate.apply(amount), amount);
    }
}
