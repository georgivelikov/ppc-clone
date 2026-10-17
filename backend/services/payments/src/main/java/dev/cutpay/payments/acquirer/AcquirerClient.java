package dev.cutpay.payments.acquirer;

import dev.cutpay.web.ApiException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to the card network. Every call has a connect and read timeout, and a circuit breaker stops
 * hammering the acquirer once it is clearly unhealthy: after enough failures calls fail immediately
 * for a while instead of each waiting for its own timeout.
 */
@Component
public class AcquirerClient {

    private static final Logger log = LoggerFactory.getLogger(AcquirerClient.class);

    public record CardInfo(String token, String brand, String last4, int expMonth, int expYear, String fingerprint) {
    }

    public sealed interface AuthResult {
        record Approved(String acquirerRef) implements AuthResult {
        }

        record Declined(String code, String message) implements AuthResult {
        }

        record RequiresAction(String acquirerRef) implements AuthResult {
        }

        /** Timeout, 5xx, network error or an open circuit: we do not know what happened at the bank. */
        record Unavailable(String reason) implements AuthResult {
        }
    }

    public sealed interface RefundResult {
        record Succeeded(String refundRef) implements RefundResult {
        }

        record Failed(String code) implements RefundResult {
        }
    }

    record AuthorizeRequest(String token, long amount, String currency, String reference) {
    }

    record AuthorizeResponse(String status, String acquirerRef, String declineCode, String message) {
    }

    record RefundRequest(String acquirerRef, long amount) {
    }

    record RefundResponse(String status, String refundRef) {
    }

    private final RestClient http;
    private final CircuitBreaker breaker;

    public AcquirerClient(AcquirerProperties properties, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.http = builder.baseUrl(properties.url()).requestFactory(factory).build();
        this.breaker = CircuitBreaker.of("acquirer", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                // A 4xx means our request was wrong, not that the acquirer is unhealthy.
                .ignoreExceptions(HttpClientErrorException.class)
                .build());
    }

    public CircuitBreaker circuitBreaker() {
        return breaker;
    }

    /** Looks up non-sensitive card details for a token. */
    public CardInfo describeToken(String token) {
        try {
            CardInfo info = http.get().uri("/tokens/{token}", token).retrieve().body(CardInfo.class);
            if (info == null) {
                throw ApiException.invalidRequest("invalid_token", "Unknown or expired card token", "token");
            }
            return info;
        } catch (HttpClientErrorException e) {
            throw ApiException.invalidRequest("invalid_token", "Unknown or expired card token", "token");
        } catch (RestClientException e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "api_error", "acquirer_unavailable",
                    "The card network is unavailable, please try again", null);
        }
    }

    public AuthResult authorize(String token, long amount, String currency, String reference) {
        try {
            AuthorizeResponse response = breaker.executeSupplier(() -> http.post().uri("/authorize")
                    .body(new AuthorizeRequest(token, amount, currency, reference))
                    .retrieve().body(AuthorizeResponse.class));
            if (response == null) {
                return new AuthResult.Unavailable("empty response");
            }
            return switch (response.status()) {
                case "approved" -> new AuthResult.Approved(response.acquirerRef());
                case "requires_action" -> new AuthResult.RequiresAction(response.acquirerRef());
                default -> new AuthResult.Declined(response.declineCode(), response.message());
            };
        } catch (CallNotPermittedException e) {
            log.warn("Acquirer circuit is open, failing fast");
            return new AuthResult.Unavailable("circuit open");
        } catch (RuntimeException e) {
            log.warn("Acquirer authorize call failed: {}", e.toString());
            return new AuthResult.Unavailable(e.getClass().getSimpleName());
        }
    }

    public RefundResult refund(String acquirerRef, long amount) {
        try {
            RefundResponse response = breaker.executeSupplier(() -> http.post().uri("/refund")
                    .body(new RefundRequest(acquirerRef, amount))
                    .retrieve().body(RefundResponse.class));
            return response != null && "succeeded".equals(response.status())
                    ? new RefundResult.Succeeded(response.refundRef())
                    : new RefundResult.Failed("refund_failed");
        } catch (CallNotPermittedException e) {
            return new RefundResult.Failed("acquirer_unavailable");
        } catch (HttpClientErrorException e) {
            return new RefundResult.Failed("refund_rejected");
        } catch (RuntimeException e) {
            log.warn("Acquirer refund call failed: {}", e.toString());
            return new RefundResult.Failed("acquirer_unavailable");
        }
    }
}
