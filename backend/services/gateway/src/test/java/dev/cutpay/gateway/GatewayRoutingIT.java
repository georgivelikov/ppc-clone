package dev.cutpay.gateway;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureWebTestClient
@Testcontainers
class GatewayRoutingIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** Pretends to be the identity service: answers every request with the identity headers it received. */
    static final HttpServer UPSTREAM = startUpstream();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("cutpay.upstreams.identity", () -> "http://localhost:" + UPSTREAM.getAddress().getPort());
        registry.add("cutpay.upstreams.payments", () -> "http://localhost:" + UPSTREAM.getAddress().getPort());
        registry.add("cutpay.upstreams.acquirer", () -> "http://localhost:" + UPSTREAM.getAddress().getPort());
    }

    @Autowired
    WebTestClient client;

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Test
    void rejectsProtectedRoutesWithoutToken() {
        client.get().uri("/me").exchange().expectStatus().isUnauthorized();
        client.get().uri("/v1/merchant").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void replacesForgedIdentityHeadersWithTokenClaims() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("usr_real").claim("mid", "mer_real").claim("role", "owner")))
                .get().uri("/me")
                .header("X-Merchant-Id", "mer_evil")
                .header("X-Role", "owner")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("user=usr_real merchant=mer_real role=owner");
    }

    @Test
    void stripsForgedHeadersOnPublicRoutes() {
        client.post().uri("/auth/login")
                .header("X-Merchant-Id", "mer_evil")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("user=null merchant=null role=null");
    }

    @Test
    void checkoutAndCardVaultArePublicButMerchantPaymentRoutesAreNot() {
        client.get().uri("/checkout/sessions/pay_x_secret_y").exchange().expectStatus().isOk();
        client.post().uri("/vault/tokenize").exchange().expectStatus().isOk();
        client.post().uri("/vault/3ds/challenge/auth_1/complete").exchange().expectStatus().isOk();

        client.get().uri("/v1/payment_intents").exchange().expectStatus().isUnauthorized();
        client.post().uri("/v1/refunds").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void internalAndAcquirerAdminEndpointsAreNeverRouted() {
        var authenticated = client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("usr_1").claim("mid", "mer_1")));
        authenticated.post().uri("/internal/3ds/callback").exchange().expectStatus().isNotFound();
        authenticated.post().uri("/vault/admin/config").exchange().expectStatus().isNotFound();
        authenticated.post().uri("/vault/authorize").exchange().expectStatus().isNotFound();
        authenticated.post().uri("/vault/refund").exchange().expectStatus().isNotFound();
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                var h = exchange.getRequestHeaders();
                byte[] body = "user=%s merchant=%s role=%s".formatted(
                        h.getFirst("X-User-Id"), h.getFirst("X-Merchant-Id"), h.getFirst("X-Role"))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
