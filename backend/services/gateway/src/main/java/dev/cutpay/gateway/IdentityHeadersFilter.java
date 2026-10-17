package dev.cutpay.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Tells downstream services who is calling, based only on the verified JWT.
 *
 * <p>Any {@code X-User-Id}, {@code X-Merchant-Id} or {@code X-Role} sent by the client is discarded first,
 * otherwise a caller could impersonate another merchant by setting the header themselves.
 * The original {@code Authorization} header is forwarded, so services can verify the token again.
 */
@Component
public class IdentityHeadersFilter implements GlobalFilter, Ordered {

    static final String USER_ID = "X-User-Id";
    static final String MERCHANT_ID = "X-Merchant-Id";
    static final String ROLE = "X-Role";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> ((JwtAuthenticationToken) auth).getToken())
                .map(jwt -> withIdentity(exchange, jwt))
                .defaultIfEmpty(withoutIdentity(exchange))
                .flatMap(chain::filter);
    }

    private static ServerWebExchange withIdentity(ServerWebExchange exchange, Jwt jwt) {
        return exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove(USER_ID);
            headers.remove(MERCHANT_ID);
            headers.remove(ROLE);
            headers.set(USER_ID, jwt.getSubject());
            headers.set(MERCHANT_ID, jwt.getClaimAsString("mid"));
            headers.set(ROLE, jwt.getClaimAsString("role"));
        })).build();
    }

    private static ServerWebExchange withoutIdentity(ServerWebExchange exchange) {
        return exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove(USER_ID);
            headers.remove(MERCHANT_ID);
            headers.remove(ROLE);
        })).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
