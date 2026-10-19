package dev.cutpay.acquirer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cutpay.acquirer")
public record AcquirerProperties(long latencyMs, double failureRate, Duration timeoutCardDelay, String threeDsCode,
                                 Duration blikDelay) {
}
