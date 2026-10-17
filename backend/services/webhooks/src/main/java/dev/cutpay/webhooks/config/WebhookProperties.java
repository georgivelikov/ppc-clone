package dev.cutpay.webhooks.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param batchSize     deliveries claimed per poll
 * @param requestTimeout per-attempt HTTP timeout
 * @param lease         how long a claimed delivery stays reserved before another worker may take it
 * @param giveUpAfter   retries stop this long after the event was created
 * @param disableAfter  an endpoint that has failed continuously for this long is disabled
 */
@ConfigurationProperties("cutpay.webhooks")
public record WebhookProperties(Duration pollInterval, int batchSize, Duration requestTimeout, Duration lease,
                                Duration giveUpAfter, Duration disableAfter, String apiVersion) {

    public WebhookProperties {
        if (batchSize <= 0) {
            batchSize = 20;
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(10);
        }
        if (lease == null) {
            lease = Duration.ofMinutes(2);
        }
        if (giveUpAfter == null) {
            giveUpAfter = Duration.ofDays(3);
        }
        if (disableAfter == null) {
            disableAfter = Duration.ofDays(3);
        }
        if (apiVersion == null) {
            apiVersion = "2026-10-01";
        }
    }
}
