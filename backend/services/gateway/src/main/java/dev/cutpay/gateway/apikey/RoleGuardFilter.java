package dev.cutpay.gateway.apikey;

import dev.cutpay.gateway.IdentityHeadersFilter;
import java.nio.charset.StandardCharsets;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Refuses what the signed-in member's role does not allow, before the request reaches a service. */
@Component
public class RoleGuardFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String role = exchange.getRequest().getHeaders().getFirst(IdentityHeadersFilter.ROLE);
        var request = exchange.getRequest();
        if (RolePolicy.allows(role, request.getMethod(), request.getPath().pathWithinApplication().value())) {
            return chain.filter(exchange);
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = ("{\"error\":{\"type\":\"permission_error\",\"code\":\"insufficient_role\","
                + "\"message\":\"Your role does not allow this. Ask an owner or admin.\"}}");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    /** After {@link IdentityHeadersFilter}, which sets the role from the verified token. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
