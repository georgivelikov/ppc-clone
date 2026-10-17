package dev.cutpay.gateway.apikey;

import dev.cutpay.gateway.apikey.ApiKeyResolver.Resolution;
import dev.cutpay.gateway.requestlog.RequestLogEntry;
import dev.cutpay.gateway.requestlog.RequestLogSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Lets machines call the API with an {@code Authorization: Bearer sk_test_...} header
 * (docs/adr/0009-api-keys.md).
 *
 * <p>Runs before Spring Security. A recognised key is verified through the identity service and the request
 * is rewritten to carry the short-lived JWT issued for that key, so everything after this filter (the security
 * chain, {@code IdentityHeadersFilter}, downstream services) sees an ordinary verified JWT and needs no
 * API-key logic of its own. Anything that is not a key passes through untouched.
 */
@Component
@Order(ApiKeyAuthFilter.ORDER)
public class ApiKeyAuthFilter implements WebFilter {

    /** Spring Security's filter chain sits at -100; this has to run earlier. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    private static final String BEARER = "Bearer ";
    private static final Pattern KEY_SHAPE = Pattern.compile("^(sk|pk|rk)_(test|live)_[0-9A-Za-z]{32}$");
    private static final Resolution UNAVAILABLE =
            new Resolution(false, "unavailable", null, false, null, List.of(), null);

    private final ApiKeyResolver resolver;
    private final RequestLogSink requestLog;
    private final Clock clock;

    public ApiKeyAuthFilter(ApiKeyResolver resolver, RequestLogSink requestLog, Clock clock) {
        this.resolver = resolver;
        this.requestLog = requestLog;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return chain.filter(exchange);
        }
        String token = header.substring(BEARER.length()).trim();
        if (!looksLikeKey(token)) {
            return chain.filter(exchange); // an ordinary JWT
        }
        if (!KEY_SHAPE.matcher(token).matches()) {
            return invalidKey(exchange);
        }
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (token.startsWith("pk_")) {
            // A publishable key sits in page source, so it never grants authenticated access. On the public
            // checkout and vault routes it is simply ignored; anywhere else it is refused.
            return KeyPolicy.isPublishableAllowed(path)
                    ? chain.filter(exchange.mutate().request(r -> r.headers(h -> h.remove(HttpHeaders.AUTHORIZATION)))
                            .build())
                    : reject(exchange, HttpStatus.FORBIDDEN, "permission_error", "publishable_key_not_allowed",
                            "Publishable keys can only be used for checkout and card tokenization");
        }
        Instant started = clock.instant();
        return resolver.resolve(token)
                .onErrorReturn(UNAVAILABLE)
                .flatMap(resolution -> {
                    if (resolution == UNAVAILABLE) {
                        return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "api_error", "auth_unavailable",
                                "Authentication is temporarily unavailable");
                    }
                    if (!resolution.valid()) {
                        return invalidKey(exchange);
                    }
                    if (KeyPolicy.isTeamOnly(path)) {
                        return rejectAndLog(exchange, resolution, path, started, "api_key_not_allowed",
                                "This endpoint cannot be called with an API key");
                    }
                    if (!KeyPolicy.allows(resolution.scopes(), exchange.getRequest().getMethod(), path)) {
                        return rejectAndLog(exchange, resolution, path, started, "insufficient_scope",
                                "This API key does not have permission for this request");
                    }
                    return chain.filter(exchange.mutate().request(request -> request.headers(
                            headers -> headers.set(HttpHeaders.AUTHORIZATION, BEARER + resolution.token()))).build());
                });
    }

    private static boolean looksLikeKey(String token) {
        return token.startsWith("sk_") || token.startsWith("pk_") || token.startsWith("rk_");
    }

    /**
     * A refusal for a key we recognise is shown in the developer's request log like any other call: "why did my
     * restricted key get a 403" is exactly what the log is for. (Requests never reach the routed-call log filter
     * once rejected here.)
     */
    private Mono<Void> rejectAndLog(ServerWebExchange exchange, Resolution key, String path, Instant started,
                                    String code, String message) {
        var request = exchange.getRequest();
        requestLog.publish(new RequestLogEntry(key.merchantId(), key.livemode(), request.getMethod().name(), path,
                HttpStatus.FORBIDDEN.value(), Duration.between(started, clock.instant()).toMillis(), key.keyId(),
                "api_key", request.getHeaders().getFirst("Idempotency-Key"),
                request.getHeaders().getFirst("X-Request-Id"), started));
        return reject(exchange, HttpStatus.FORBIDDEN, "permission_error", code, message);
    }

    private static Mono<Void> invalidKey(ServerWebExchange exchange) {
        return reject(exchange, HttpStatus.UNAUTHORIZED, "authentication_error", "invalid_api_key",
                "Invalid API key");
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String type, String code,
                                     String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":{\"type\":\"%s\",\"code\":\"%s\",\"message\":\"%s\"}}".formatted(type, code, message);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
