package dev.cutpay.payments.acquirer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cutpay.acquirer")
public record AcquirerProperties(String url, Duration connectTimeout, Duration readTimeout) {
}
