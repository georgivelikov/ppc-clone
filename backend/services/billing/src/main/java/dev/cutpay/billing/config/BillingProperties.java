package dev.cutpay.billing.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cutpay.billing")
public record BillingProperties(String checkoutUrl, String paymentsUrl, Duration jobPollInterval,
                                Duration incompleteExpiry) {
}
