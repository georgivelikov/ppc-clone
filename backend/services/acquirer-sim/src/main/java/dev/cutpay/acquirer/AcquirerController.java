package dev.cutpay.acquirer;

import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * A pretend card network + issuing bank. It keeps everything in memory: restart it and all tokens and
 * pending challenges are gone, which is fine for a simulator.
 */
@RestController
class AcquirerController {

    private static final Logger log = LoggerFactory.getLogger(AcquirerController.class);

    record StoredCard(String pan, int expMonth, int expYear) {
    }

    record PendingChallenge(String authorizationId, long amount, String currency) {
    }

    record Runtime(long latencyMs, double failureRate) {
    }

    record TokenizeRequest(@NotBlank String number, @Min(1) int expMonth, @Min(0) int expYear, String cvc) {
    }

    record TokenizeResponse(String token, String brand, String last4, int expMonth, int expYear, String fingerprint) {
    }

    record AuthorizeRequest(@NotBlank String token, @Min(1) long amount, @NotBlank String currency, String reference) {
    }

    record AuthorizeResponse(String status, String acquirerRef, String declineCode, String message) {
    }

    record ChallengeRequest(@NotNull String code) {
    }

    record ChallengeResponse(String status, String declineCode, String message) {
    }

    record RefundRequest(@NotBlank String acquirerRef, @Min(1) long amount) {
    }

    record RefundResponse(String status, String refundRef) {
    }

    record ConfigRequest(Long latencyMs, Double failureRate) {
    }

    private final Map<String, StoredCard> tokens = new ConcurrentHashMap<>();
    private final Map<String, PendingChallenge> challenges = new ConcurrentHashMap<>();
    private final Map<String, Long> authorizations = new ConcurrentHashMap<>();
    private final Map<String, Long> refunded = new ConcurrentHashMap<>();
    private final AtomicReference<Runtime> runtime;
    private final AcquirerProperties properties;
    private final RestClient payments;

    AcquirerController(AcquirerProperties properties, RestClient.Builder builder,
                       @Value("${cutpay.payments-url}") String paymentsUrl) {
        this.properties = properties;
        this.runtime = new AtomicReference<>(new Runtime(properties.latencyMs(), properties.failureRate()));
        this.payments = builder.baseUrl(paymentsUrl).build();
    }

    /** Stands in for a PCI vault: the only place a card number is ever accepted. */
    @PostMapping("/tokenize")
    TokenizeResponse tokenize(@Valid @RequestBody TokenizeRequest request) {
        String pan = request.number().replaceAll("[\\s-]", "");
        if (!pan.matches("\\d{12,19}") || !TestCards.luhnValid(pan)) {
            throw ApiException.invalidRequest("invalid_card_number", "The card number is not valid", "number");
        }
        if (request.expMonth() > 12) {
            throw ApiException.invalidRequest("invalid_expiry", "The expiry month is not valid", "exp_month");
        }
        int expYear = request.expYear() < 100 ? 2000 + request.expYear() : request.expYear();
        String token = "tok_" + UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, new StoredCard(pan, request.expMonth(), expYear));
        return new TokenizeResponse(token, TestCards.brand(pan), pan.substring(pan.length() - 4), request.expMonth(),
                expYear, fingerprint(pan));
    }

    @PostMapping("/authorize")
    AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest request) throws InterruptedException {
        StoredCard card = tokens.get(request.token());
        if (card == null) {
            throw ApiException.invalidRequest("invalid_token", "Unknown or expired card token", "token");
        }
        Runtime config = runtime.get();
        if (config.latencyMs() > 0) {
            Thread.sleep(config.latencyMs());
        }
        if (config.failureRate() > 0 && Math.random() < config.failureRate()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Injected failure");
        }

        TestCards.Outcome outcome = TestCards.forPan(card.pan());
        switch (outcome.behaviour()) {
            case PROCESSING_ERROR -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "processing_error");
            case TIMEOUT -> Thread.sleep(properties.timeoutCardDelay());
            default -> { }
        }
        if (YearMonth.of(card.expYear(), card.expMonth()).isBefore(YearMonth.now())) {
            return declined("expired_card", "Your card has expired.");
        }
        if (outcome.behaviour() == TestCards.Behaviour.DECLINE) {
            return declined(outcome.declineCode(), outcome.message());
        }

        String ref = "auth_" + UUID.randomUUID().toString().replace("-", "");
        if (outcome.behaviour() == TestCards.Behaviour.THREE_DS) {
            challenges.put(ref, new PendingChallenge(ref, request.amount(), request.currency()));
            return new AuthorizeResponse("requires_action", ref, null, "3-D Secure authentication required");
        }
        authorizations.put(ref, request.amount());
        return new AuthorizeResponse("approved", ref, null, null);
    }

    /**
     * The customer's answer on the fake bank page. The result is pushed to the payments service, like an
     * issuer's 3DS server calling the PSP back.
     */
    @PostMapping("/3ds/challenge/{id}/complete")
    ChallengeResponse completeChallenge(@PathVariable String id, @Valid @RequestBody ChallengeRequest request) {
        PendingChallenge challenge = challenges.get(id);
        if (challenge == null) {
            throw ApiException.notFound("challenge_not_found", "Unknown or already completed challenge");
        }
        ChallengeResponse result;
        if (properties.threeDsCode().equals(request.code())) {
            authorizations.put(id, challenge.amount());
            result = new ChallengeResponse("approved", null, null);
        } else {
            result = new ChallengeResponse("declined", "authentication_failed", "3-D Secure authentication failed.");
        }
        challenges.remove(id);
        notifyPayments(id, result);
        return result;
    }

    @PostMapping("/capture")
    AuthorizeResponse capture(@Valid @RequestBody RefundRequest request) {
        if (!authorizations.containsKey(request.acquirerRef())) {
            throw ApiException.notFound("authorization_not_found", "Unknown authorization");
        }
        return new AuthorizeResponse("approved", request.acquirerRef(), null, null);
    }

    @PostMapping("/refund")
    RefundResponse refund(@Valid @RequestBody RefundRequest request) {
        Long authorized = authorizations.get(request.acquirerRef());
        if (authorized == null) {
            throw ApiException.notFound("authorization_not_found", "Unknown authorization");
        }
        synchronized (refunded) {
            long already = refunded.getOrDefault(request.acquirerRef(), 0L);
            if (already + request.amount() > authorized) {
                throw ApiException.invalidRequest("refund_exceeds_charge", "Refund is larger than the charge",
                        "amount");
            }
            refunded.put(request.acquirerRef(), already + request.amount());
        }
        return new RefundResponse("succeeded", "ref_" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** Chaos knobs for later phases; no authentication because this service is never exposed by the gateway. */
    @PostMapping("/admin/config")
    Runtime configure(@RequestBody ConfigRequest request) {
        return runtime.updateAndGet(current -> new Runtime(
                request.latencyMs() != null ? request.latencyMs() : current.latencyMs(),
                request.failureRate() != null ? request.failureRate() : current.failureRate()));
    }

    private void notifyPayments(String challengeId, ChallengeResponse result) {
        try {
            payments.post().uri("/internal/3ds/callback")
                    .body(Map.of("acquirer_ref", challengeId, "status", result.status(),
                            "decline_code", result.declineCode() == null ? "" : result.declineCode(),
                            "message", result.message() == null ? "" : result.message()))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Could not deliver 3DS result for {} to payments", challengeId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach payments");
        }
    }

    private static AuthorizeResponse declined(String code, String message) {
        return new AuthorizeResponse("declined", null, code, message);
    }

    private static String fingerprint(String pan) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(pan.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
