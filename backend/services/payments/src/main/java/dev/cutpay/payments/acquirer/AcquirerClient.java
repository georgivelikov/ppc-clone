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

    /** {@code cardType} / {@code cardRegion} come from the issuer BIN lookup and drive the fee schedule. */
    public record CardInfo(String token, String brand, String last4, int expMonth, int expYear, String fingerprint,
                           String cardType, String cardRegion, String walletType, Integer chargebackAfterDays) {

        public CardInfo(String token, String brand, String last4, int expMonth, int expYear, String fingerprint,
                        String cardType, String cardRegion) {
            this(token, brand, last4, expMonth, expYear, fingerprint, cardType, cardRegion, null, null);
        }
    }

    /** The first answer to a BLIK code. An approved code is not final: the customer still has to confirm in their app. */
    public sealed interface BlikResult {
        record Pending(String acquirerRef) implements BlikResult {
        }

        record Declined(String code, String message) implements BlikResult {
        }

        /** The code is not a valid BLIK code; the customer can type another. */
        record InvalidCode(String message) implements BlikResult {
        }

        record Unavailable(String reason) implements BlikResult {
        }
    }

    record BlikRequest(String code, long amount, String currency, String reference) {
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

    record AuthorizeRequest(String token, long amount, String currency, String reference, String channel,
                            boolean offSession) {
    }

    record VerifyRequest(String token, String channel) {
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
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout()).build());
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

    public AuthResult authorize(String token, long amount, String currency, String reference, String channel) {
        return authorize(token, amount, currency, reference, channel, false);
    }

    /** {@code offSession}: the customer is not there, so the bank cannot ask them to authenticate. */
    public AuthResult authorize(String token, long amount, String currency, String reference, String channel,
                                boolean offSession) {
        return call("authorize", () -> http.post().uri("/authorize")
                .body(new AuthorizeRequest(token, amount, currency, reference, channel, offSession))
                .retrieve().body(AuthorizeResponse.class));
    }

    public BlikResult blikCharge(String code, long amount, String currency, String reference) {
        try {
            AuthorizeResponse response = breaker.executeSupplier(() -> http.post().uri("/blik/charge")
                    .body(new BlikRequest(code, amount, currency, reference)).retrieve().body(AuthorizeResponse.class));
            if (response == null) {
                return new BlikResult.Unavailable("empty response");
            }
            return switch (response.status()) {
                case "requires_action" -> new BlikResult.Pending(response.acquirerRef());
                case "invalid_code" -> new BlikResult.InvalidCode(response.message());
                default -> new BlikResult.Declined(response.declineCode(), response.message());
            };
        } catch (CallNotPermittedException e) {
            return new BlikResult.Unavailable("circuit open");
        } catch (RuntimeException e) {
            log.warn("Acquirer BLIK call failed: {}", e.toString());
            return new BlikResult.Unavailable(e.getClass().getSimpleName());
        }
    }

    /** Checks a card without charging it, for saving it. May ask the customer to authenticate (once). */
    public AuthResult verify(String token) {
        return call("verify", () -> http.post().uri("/verify")
                .body(new VerifyRequest(token, "online"))
                .retrieve().body(AuthorizeResponse.class));
    }

    private AuthResult call(String what, java.util.function.Supplier<AuthorizeResponse> request) {
        try {
            AuthorizeResponse response = breaker.executeSupplier(request);
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
            log.warn("Acquirer {} call failed: {}", what, e.toString());
            return new AuthResult.Unavailable(e.getClass().getSimpleName());
        }
    }

    /** The card network's decision on a dispute. */
    public record DisputeVerdict(String outcome, String reason) {
    }

    record DisputeRequest(String disputeId, String evidence, int files) {
    }

    /** Empty when the network could not be asked, so the caller tries again later. */
    public java.util.Optional<DisputeVerdict> evaluateDispute(String disputeId, String evidence, int files) {
        try {
            DisputeVerdict verdict = breaker.executeSupplier(() -> http.post().uri("/disputes/evaluate")
                    .body(new DisputeRequest(disputeId, evidence, files)).retrieve().body(DisputeVerdict.class));
            return java.util.Optional.ofNullable(verdict);
        } catch (RuntimeException e) {
            log.warn("Acquirer dispute call failed: {}", e.toString());
            return java.util.Optional.empty();
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

    /** Outcome of capturing or releasing a held authorisation. */
    public sealed interface HoldResult {
        record Done() implements HoldResult {
        }

        /** The acquirer refused (unknown, already captured, too large): retrying will not help. */
        record Rejected(String code) implements HoldResult {
        }

        record Unavailable() implements HoldResult {
        }
    }

    record HoldRequest(String acquirerRef, Long amount) {
    }

    /** Takes {@code amount} from a held authorisation; the remainder is released. Safe to repeat. */
    public HoldResult capture(String acquirerRef, long amount) {
        return hold("/capture", new HoldRequest(acquirerRef, amount));
    }

    /** Releases a held authorisation without taking anything. Safe to repeat. */
    public HoldResult release(String acquirerRef) {
        return hold("/void", new HoldRequest(acquirerRef, null));
    }

    private HoldResult hold(String path, HoldRequest request) {
        try {
            breaker.executeSupplier(() -> http.post().uri(path).body(request).retrieve().toBodilessEntity());
            return new HoldResult.Done();
        } catch (CallNotPermittedException e) {
            return new HoldResult.Unavailable();
        } catch (HttpClientErrorException e) {
            return new HoldResult.Rejected(e.getStatusCode().value() == 409 ? "already_captured" : "rejected");
        } catch (RuntimeException e) {
            log.warn("Acquirer {} call failed: {}", path, e.toString());
            return new HoldResult.Unavailable();
        }
    }
}
