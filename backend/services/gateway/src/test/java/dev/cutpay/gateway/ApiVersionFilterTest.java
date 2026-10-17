package dev.cutpay.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ApiVersionFilterTest {

    private final ApiVersionFilter filter = new ApiVersionFilter();
    private final AtomicReference<ServerWebExchange> passedOn = new AtomicReference<>();
    private final WebFilterChain chain = exchange -> {
        passedOn.set(exchange);
        return Mono.empty();
    };

    private MockServerWebExchange call(String version) {
        var request = MockServerHttpRequest.method(HttpMethod.GET, "/v1/balance");
        if (version != null) {
            request.header("Cutpay-Version", version);
        }
        var exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, chain).block();
        return exchange;
    }

    @Test
    void withoutTheHeaderTheLatestVersionIsUsedAndReported() {
        var exchange = call(null);
        assertThat(exchange.getResponse().getHeaders().getFirst("Cutpay-Version")).isEqualTo("2026-10-01");
        assertThat(passedOn.get().getRequest().getHeaders().getFirst("Cutpay-Version")).isEqualTo("2026-10-01");
    }

    @Test
    void aSupportedVersionIsHonouredAndForwarded() {
        var exchange = call("2026-10-01");
        assertThat(exchange.getResponse().getHeaders().getFirst("Cutpay-Version")).isEqualTo("2026-10-01");
        assertThat(passedOn.get()).isNotNull();
    }

    @Test
    void anUnknownVersionIsRefusedWithTheSupportedOnesListed() {
        var exchange = call("2019-01-01");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("invalid_api_version", "2026-10-01");
        assertThat(passedOn.get()).isNull();
    }
}
