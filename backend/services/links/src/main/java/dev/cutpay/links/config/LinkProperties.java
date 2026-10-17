package dev.cutpay.links.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cutpay.links")
public record LinkProperties(String checkoutUrl, String paymentsUrl, Duration reservationTtl,
                             Duration jobPollInterval) {
}
