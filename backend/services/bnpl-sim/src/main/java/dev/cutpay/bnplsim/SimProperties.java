package dev.cutpay.bnplsim;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cutpay.bnpl-sim")
public record SimProperties(Duration splitnowLatency, Duration lendoDecisionDelay, Duration kreditkoReviewDelay,
                            double paylatereuTimeoutRate, Duration timeoutHang, String lendoSecret,
                            List<String> splitnowBlocklist) {
}
