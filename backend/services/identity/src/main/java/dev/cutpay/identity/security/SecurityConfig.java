package dev.cutpay.identity.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                // Stateless bearer-token API. The only cookie (refresh token) is SameSite=Strict and
                // scoped to /auth, so classic CSRF does not apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/.well-known/**", "/internal/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/v1/staff/**").authenticated()
                        // Cutpay staff are a realm of their own: their tokens name no business and open nothing of one.
                        .anyRequest().access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                authentication.get() instanceof org.springframework.security.authentication.AbstractAuthenticationToken token
                                        && token.isAuthenticated()
                                        && !(token.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt
                                        && "staff".equals(jwt.getClaimAsString("realm")))
                                        && !(token instanceof org.springframework.security.authentication.AnonymousAuthenticationToken))))
                .oauth2ResourceServer(oauth -> oauth.jwt(withDefaults()));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Delegating encoder: hashes are prefixed with the algorithm ({bcrypt}...), so it can be upgraded later.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeyService keys) {
        return new NimbusJwtEncoder(keys.jwkSource());
    }

    @Bean
    JwtDecoder jwtDecoder(SigningKeyService keys) {
        return NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
    }
}
