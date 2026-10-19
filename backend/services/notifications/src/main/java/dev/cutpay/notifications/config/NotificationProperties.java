package dev.cutpay.notifications.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cutpay.notifications")
public record NotificationProperties(String smtpHost, int smtpPort, String from, String dashboardUrl,
                                     Duration retryPollInterval, int maxAttempts, Duration retryBaseDelay) {
}
