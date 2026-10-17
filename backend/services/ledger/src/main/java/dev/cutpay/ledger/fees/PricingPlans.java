package dev.cutpay.ledger.fees;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PricingPlans {

    private final JdbcClient jdbc;

    public PricingPlans(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The merchant's plan, created with the standard schedule if it does not exist yet. A payment can
     * overtake the MerchantCreated event (they travel on different topics), so this must not fail.
     */
    public PricingPlan findOrCreate(String merchantId) {
        createStandardIfMissing(merchantId);
        return jdbc.sql("""
                        SELECT eea_consumer_bps, eea_consumer_fixed, other_bps, other_fixed, delay_days
                          FROM pricing_plans WHERE merchant_id = :merchant
                        """)
                .param("merchant", merchantId)
                .query((rs, n) -> new PricingPlan(merchantId,
                        new Rate(rs.getInt("eea_consumer_bps"), rs.getLong("eea_consumer_fixed")),
                        new Rate(rs.getInt("other_bps"), rs.getLong("other_fixed")),
                        rs.getInt("delay_days")))
                .single();
    }

    public void createStandardIfMissing(String merchantId) {
        PricingPlan standard = PricingPlan.standard(merchantId);
        jdbc.sql("""
                        INSERT INTO pricing_plans (merchant_id, eea_consumer_bps, eea_consumer_fixed,
                                                   other_bps, other_fixed, delay_days)
                        VALUES (:merchant, :eeaBps, :eeaFixed, :otherBps, :otherFixed, :delay)
                        ON CONFLICT (merchant_id) DO NOTHING
                        """)
                .param("merchant", merchantId)
                .param("eeaBps", standard.eeaConsumer().bps())
                .param("eeaFixed", standard.eeaConsumer().fixedMinor())
                .param("otherBps", standard.other().bps())
                .param("otherFixed", standard.other().fixedMinor())
                .param("delay", standard.delayDays())
                .update();
    }

    public void setDelayDays(String merchantId, int delayDays) {
        createStandardIfMissing(merchantId);
        jdbc.sql("UPDATE pricing_plans SET delay_days = :delay, updated_at = now() WHERE merchant_id = :merchant")
                .param("delay", delayDays)
                .param("merchant", merchantId)
                .update();
    }
}
