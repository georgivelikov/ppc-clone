package dev.cutpay.gateway.requestlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RequestLogFilterTest {

    private final List<RequestLogEntry> logged = new ArrayList<>();
    private final RequestLogFilter filter = new RequestLogFilter(logged::add, Clock.systemUTC());

    private MockServerWebExchange exchange(String path, String... headers) {
        var request = MockServerHttpRequest.method(HttpMethod.POST, path);
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return MockServerWebExchange.from(request);
    }

    @Test
    void logsAnApiKeyCallWithItsOutcome() {
        var exchange = exchange("/v1/payment_intents?secret=abc", "X-Merchant-Id", "mer_1", "X-User-Id", "key_1",
                "X-Role", "api", "X-Livemode", "false", "Idempotency-Key", "idem-1", "X-Request-Id", "req_1");
        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.CREATED);
            return Mono.empty();
        }).block();

        assertThat(logged).hasSize(1);
        RequestLogEntry entry = logged.get(0);
        assertThat(entry.merchantId()).isEqualTo("mer_1");
        assertThat(entry.method()).isEqualTo("POST");
        assertThat(entry.path()).isEqualTo("/v1/payment_intents"); // no query string
        assertThat(entry.status()).isEqualTo(201);
        assertThat(entry.actor()).isEqualTo("key_1");
        assertThat(entry.actorType()).isEqualTo("api_key");
        assertThat(entry.livemode()).isFalse();
        assertThat(entry.idempotencyKey()).isEqualTo("idem-1");
        assertThat(entry.requestId()).isEqualTo("req_1");
        assertThat(entry.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void marksLiveKeysAndDashboardSessions() {
        filter.filter(exchange("/v1/payouts", "X-Merchant-Id", "mer_1", "X-User-Id", "key_2", "X-Role", "api",
                "X-Livemode", "true"), ex -> Mono.empty()).block();
        filter.filter(exchange("/v1/balance", "X-Merchant-Id", "mer_1", "X-User-Id", "usr_1", "X-Role", "owner"),
                ex -> Mono.empty()).block();
        assertThat(logged.get(0).livemode()).isTrue();
        assertThat(logged.get(1).actorType()).isEqualTo("user");
        assertThat(logged.get(1).livemode()).isFalse();
    }

    @Test
    void callsWithoutAMerchantAreNotLogged() {
        filter.filter(exchange("/auth/login"), ex -> Mono.empty()).block();
        assertThat(logged).isEmpty();
    }

    @Test
    void failuresAreLoggedWithTheirStatus() {
        var exchange = exchange("/v1/refunds", "X-Merchant-Id", "mer_1", "X-User-Id", "key_1", "X-Role", "api");
        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
            return Mono.empty();
        }).block();
        assertThat(logged.get(0).status()).isEqualTo(502);
    }
}
