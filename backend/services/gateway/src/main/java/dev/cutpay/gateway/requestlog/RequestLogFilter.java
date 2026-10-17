package dev.cutpay.gateway.requestlog;


import dev.cutpay.gateway.IdentityHeadersFilter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Records every authenticated call that is routed to a service. It runs right after the identity filter, so
 * the merchant, actor and mode are the verified ones, and it logs once the response is done. Calls without a
 * merchant (login, the public checkout) are not logged: there is nobody to show them to.
 */
@Component
public class RequestLogFilter implements GlobalFilter, Ordered {

    private static final int MAX_PATH = 300;

    private final RequestLogSink sink;
    private final Clock clock;

    public RequestLogFilter(RequestLogSink sink, Clock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Instant started = clock.instant();
        // The headers set by IdentityHeadersFilter are read now: they are the verified ones.
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String merchant = headers.getFirst(IdentityHeadersFilter.MERCHANT_ID);
        if (merchant == null) {
            return chain.filter(exchange);
        }
        String actor = headers.getFirst(IdentityHeadersFilter.USER_ID);
        boolean apiKey = "api".equals(headers.getFirst(IdentityHeadersFilter.ROLE));
        boolean livemode = "true".equals(headers.getFirst(IdentityHeadersFilter.LIVEMODE));
        String idempotencyKey = headers.getFirst("Idempotency-Key");
        String requestId = headers.getFirst("X-Request-Id");
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        return chain.filter(exchange).doFinally(signal -> {
            var status = exchange.getResponse().getStatusCode();
            sink.publish(new RequestLogEntry(merchant, livemode, method, truncate(path), status == null ? 200
                    : status.value(), Duration.between(started, clock.instant()).toMillis(), actor,
                    apiKey ? "api_key" : "user", idempotencyKey, requestId, started));
        });
    }

    private static String truncate(String path) {
        return path.length() <= MAX_PATH ? path : path.substring(0, MAX_PATH);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
