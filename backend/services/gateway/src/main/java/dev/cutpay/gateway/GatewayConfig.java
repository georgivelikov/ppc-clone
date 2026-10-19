package dev.cutpay.gateway;

import java.net.InetSocketAddress;
import java.time.Clock;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class GatewayConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Login, signup, refresh and the public key set are reachable without a token.
                        .pathMatchers("/auth/**", "/.well-known/**").permitAll()
                        // The hosted checkout, payment links and the card vault are used by anonymous customers.
                        .pathMatchers("/checkout/**", "/vault/**", "/links/**", "/invoices/**").permitAll()
                        // Buy-now-pay-later providers report their decisions here; the bnpl service checks each callback itself.
                        .pathMatchers("/bnpl/callback/**").permitAll()
                        // A finished report is fetched with a signed link that expires; the reporting service checks the signature.
                        .pathMatchers("/v1/reporting/files/**").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> { }))
                .build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Rate-limit buckets are per client address. */
    @Bean
    KeyResolver clientIpKeyResolver() {
        return exchange -> {
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            return Mono.just(remote != null && remote.getAddress() != null
                    ? remote.getAddress().getHostAddress()
                    : "unknown");
        };
    }
}
