package dev.cutpay.payouts.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param bankDelay    how long the fake bank takes to settle a transfer (simulated time)
 * @param instantFeeBps fee for a manual payout in basis points; 100 = 1%
 */
@ConfigurationProperties("cutpay.payouts")
public record PayoutProperties(Duration jobPollInterval, Duration bankDelay, int instantFeeBps) {

    public PayoutProperties {
        if (bankDelay == null) {
            bankDelay = Duration.ofMinutes(1);
        }
        if (instantFeeBps == 0) {
            instantFeeBps = 100;
        }
    }
}
