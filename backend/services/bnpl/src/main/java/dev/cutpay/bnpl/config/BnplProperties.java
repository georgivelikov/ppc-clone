package dev.cutpay.bnpl.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cutpay.bnpl")
public record BnplProperties(String paymentsUrl, Duration offersDeadline, Duration providerTimeout, String defaultCountry,
                             String callbackBaseUrl, Duration jobPollInterval, Duration applicationExpiry, Duration settlementDelay,
                             List<Integer> kreditkoTerms, Providers providers) {

    /** Where each provider lives. */
    public record Providers(String splitnowUrl, String lendoUrl, String kreditkoUrl, String paylatereuUrl,
                            String lendoSecret) {
    }
}
