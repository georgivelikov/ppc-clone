package dev.cutpay.bnpl.model;

import java.util.List;

/**
 * One way to pay a price in parts, in Cutpay's own terms. Every amount is in minor units whatever the provider calls
 * them; turning a provider's shape into this one is the adapter's whole job.
 *
 * @param plan         unique across providers, for example {@code splitnow_3} or {@code lendo_12}
 * @param instalments  what the customer pays, first payment first
 * @param intervalDays days between payments (for a pay-later plan, the wait before the single payment)
 * @param totalPayable what the customer pays in the end; more than the price when there is interest
 * @param merchantFee  what the provider keeps; the merchant is paid the price minus this
 */
public record Offer(String provider, String plan, int instalmentCount, List<Long> instalments, int intervalDays,
                    double aprPercent, long totalPayable, long merchantFee, String currency) {

    /** What financing costs the customer on top of the price. */
    public long customerCost(long price) {
        return totalPayable - price;
    }
}
