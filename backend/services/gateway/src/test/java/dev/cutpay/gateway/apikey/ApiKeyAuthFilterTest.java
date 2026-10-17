package dev.cutpay.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cutpay.gateway.apikey.ApiKeyResolver.Resolution;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ApiKeyAuthFilterTest {

    private static final String SECRET = "sk_test_" + "a".repeat(32);
    private static final String PUBLISHABLE = "pk_test_" + "b".repeat(32);
    private static final String DOWN = "sk_test_" + "d".repeat(32);
    private static final String RESTRICTED = "rk_live_" + "c".repeat(32);

    private final AtomicReference<ServerWebExchange> passedOn = new AtomicReference<>();
    private final WebFilterChain chain = exchange -> {
        passedOn.set(exchange);
        return Mono.empty();
    };

    /** Stands in for identity: knows three keys and nothing else. */
    private final ApiKeyResolver resolver = new ApiKeyResolver("http://unused") {
        @Override
        public Mono<Resolution> resolve(String rawKey) {
            if (rawKey.equals(SECRET)) {
                return Mono.just(new Resolution(true, "key_1", "mer_1", false, "secret", List.of("*"), "jwt-1"));
            }
            if (rawKey.equals(RESTRICTED)) {
                return Mono.just(new Resolution(true, "key_2", "mer_1", true, "restricted",
                        List.of("payments:read"), "jwt-2"));
            }
            if (rawKey.equals(DOWN)) {
                return Mono.error(new IllegalStateException("identity is down"));
            }
            return Mono.just(Resolution.INVALID);
        }
    };

    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(resolver);

    private MockServerWebExchange call(HttpMethod method, String path, String authorization) {
        var request = MockServerHttpRequest.method(method, path);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, chain).block();
        return exchange;
    }

    @Test
    void swapsAValidKeyForItsJwt() {
        call(HttpMethod.POST, "/v1/payment_intents", "Bearer " + SECRET);
        assertThat(passedOn.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer jwt-1");
    }

    @Test
    void leavesOrdinaryJwtsAlone() {
        call(HttpMethod.GET, "/v1/payment_intents", "Bearer eyJhbGciOiJSUzI1NiJ9.e30.sig");
        assertThat(passedOn.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer eyJhbGciOiJSUzI1NiJ9.e30.sig");
    }

    @Test
    void requestsWithoutCredentialsPassThrough() {
        call(HttpMethod.GET, "/checkout/sessions/x", null);
        assertThat(passedOn.get()).isNotNull();
    }

    @Test
    void rejectsUnknownAndMalformedKeys() {
        assertThat(call(HttpMethod.GET, "/v1/payment_intents", "Bearer sk_test_" + "z".repeat(32))
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/v1/payment_intents", "Bearer sk_test_short")
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(passedOn.get()).isNull();
    }

    @Test
    void failsClosedWhenIdentityIsUnavailable() {
        assertThat(call(HttpMethod.GET, "/v1/payment_intents", "Bearer " + DOWN)
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(passedOn.get()).isNull();
    }

    @Test
    void restrictedKeyIsHeldToItsScopes() {
        assertThat(call(HttpMethod.GET, "/v1/payment_intents", "Bearer " + RESTRICTED)
                .getResponse().getStatusCode()).isNull(); // let through
        assertThat(passedOn.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer jwt-2");

        passedOn.set(null);
        assertThat(call(HttpMethod.POST, "/v1/payment_intents", "Bearer " + RESTRICTED)
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/v1/payouts", "Bearer " + RESTRICTED)
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(passedOn.get()).isNull();
    }

    @Test
    void apiKeysCannotManageApiKeys() {
        assertThat(call(HttpMethod.POST, "/v1/api_keys", "Bearer " + SECRET)
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(passedOn.get()).isNull();
    }

    @Test
    void publishableKeyIsRefusedOnAuthenticatedRoutes() {
        assertThat(call(HttpMethod.GET, "/v1/payment_intents", "Bearer " + PUBLISHABLE)
                .getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(passedOn.get()).isNull();
    }

    @Test
    void publishableKeyIsIgnoredOnPublicRoutes() {
        call(HttpMethod.POST, "/vault/tokenize", "Bearer " + PUBLISHABLE);
        assertThat(passedOn.get().getRequest().getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
    }
}
