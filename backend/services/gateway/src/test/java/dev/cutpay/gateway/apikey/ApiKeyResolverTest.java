package dev.cutpay.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiKeyResolverTest {

    private static final String KEY = "sk_test_" + "a".repeat(32);

    private HttpServer identity;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;

    @BeforeEach
    void startIdentity() throws IOException {
        identity = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        identity.createContext("/internal/api_keys/authenticate", exchange -> {
            calls.incrementAndGet();
            byte[] body = (status == 200
                    ? "{\"key_id\":\"key_1\",\"merchant_id\":\"mer_1\",\"livemode\":true,\"type\":\"restricted\","
                            + "\"scopes\":[\"payments:read\"],\"token\":\"jwt\",\"expires_in\":300}"
                    : "{\"error\":{\"code\":\"invalid_api_key\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        identity.start();
    }

    @AfterEach
    void stopIdentity() {
        identity.stop(0);
    }

    private ApiKeyResolver resolver() {
        return new ApiKeyResolver("http://localhost:" + identity.getAddress().getPort());
    }

    @Test
    void readsIdentitysSnakeCaseAnswer() {
        var resolution = resolver().resolve(KEY).block();
        assertThat(resolution.valid()).isTrue();
        assertThat(resolution.keyId()).isEqualTo("key_1");
        assertThat(resolution.merchantId()).isEqualTo("mer_1");
        assertThat(resolution.livemode()).isTrue();
        assertThat(resolution.scopes()).containsExactly("payments:read");
        assertThat(resolution.token()).isEqualTo("jwt");
    }

    @Test
    void cachesResultsSoIdentityIsNotCalledPerRequest() {
        var resolver = resolver();
        resolver.resolve(KEY).block();
        resolver.resolve(KEY).block();
        assertThat(calls).hasValue(1);
    }

    @Test
    void unknownKeysAreInvalidAndCachedToo() {
        status = 401;
        var resolver = resolver();
        assertThat(resolver.resolve(KEY).block().valid()).isFalse();
        assertThat(resolver.resolve(KEY).block().valid()).isFalse();
        assertThat(calls).hasValue(1);
    }

    @Test
    void identityFailuresAreErrorsAndAreNotCached() {
        status = 500;
        var resolver = resolver();
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> resolver.resolve(KEY).block());
        status = 200;
        assertThat(resolver.resolve(KEY).block().valid()).isTrue();
    }
}
