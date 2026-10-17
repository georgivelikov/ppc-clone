package dev.cutpay.ledger.fees;

/** How a pricing plan turns a payment into a fee, in minor units of the payment's currency. */
public interface FeeStrategy {

    long feeFor(long amount, CardProfile card);
}
