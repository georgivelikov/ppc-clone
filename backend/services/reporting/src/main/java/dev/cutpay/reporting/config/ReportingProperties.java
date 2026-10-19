package dev.cutpay.reporting.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cutpay.reporting")
public record ReportingProperties(String signingSecret, Duration linkTtl, Duration jobPollInterval, String dashboardUrl) {
}
