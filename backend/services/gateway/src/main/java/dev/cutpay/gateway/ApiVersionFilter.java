package dev.cutpay.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Date-based API versioning. A caller can pin the behaviour they integrated against with
 * {@code Cutpay-Version: 2026-10-01}; without the header they get the latest. There is only one version for
 * now, but the mechanism is complete: unknown versions are refused, the version actually used is echoed in every
 * response, and it is forwarded to the services so a future breaking change can branch on it.
 */
@Component
@Order(ApiVersionFilter.ORDER)
public class ApiVersionFilter implements WebFilter {

    static final String HEADER = "Cutpay-Version";
    static final List<String> SUPPORTED = List.of("2026-10-01");
    static final String LATEST = SUPPORTED.get(SUPPORTED.size() - 1);
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requested = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (requested != null && !SUPPORTED.contains(requested.trim())) {
            var response = exchange.getResponse();
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            response.getHeaders().set(HEADER, LATEST);
            String body = "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"invalid_api_version\","
                    + "\"message\":\"Unsupported Cutpay-Version. Supported versions: " + String.join(", ", SUPPORTED)
                    + "\",\"param\":\"Cutpay-Version\"}}";
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
        }
        String version = requested == null ? LATEST : requested.trim();
        exchange.getResponse().getHeaders().set(HEADER, version);
        return chain.filter(exchange.mutate().request(r -> r.headers(h -> h.set(HEADER, version))).build());
    }
}
