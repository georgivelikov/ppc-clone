package dev.cutpay.identity.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cutpay.auth")
public record AuthProperties(Duration accessTokenTtl, Duration refreshTokenTtl, boolean secureCookies) {
}
