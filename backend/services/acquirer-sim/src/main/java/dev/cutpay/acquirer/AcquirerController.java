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
import org.springframework.web.bind.annotation.GetMapping;
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

    record TokenizeResponse(String token, String brand, String last4, int expMonth, int expYear, String fingerprint,
                            String cardType, String cardRegion) {
    }

    record VerifyRequest(@NotBlank String token, String channel) {
    }

    record AuthorizeRequest(@NotBlank String token, @Min(1) long amount, @NotBlank String currency, String reference,
                            String channel, Boolean offSession) {
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

    record VoidRequest(@NotBlank String acquirerRef) {
    }

    record ConfigRequest(Long latencyMs, Double failureRate) {
    }

    private final Map<String, StoredCard> tokens = new ConcurrentHashMap<>();
    private final Map<String, PendingChallenge> challenges = new ConcurrentHashMap<>();
    private final Map<String, Long> authorizations = new ConcurrentHashMap<>();
    private final Map<String, Long> refunded = new ConcurrentHashMap<>();
    /** Amount taken from an authorisation; guards the "capture once" rule. */
    private final Map<String, Long> captured = new java.util.HashMap<>();
    private final java.util.Set<String> voided = ConcurrentHashMap.newKeySet();
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
        BinTable.BinInfo bin = BinTable.lookup(pan);
        return new TokenizeResponse(token, TestCards.brand(pan), pan.substring(pan.length() - 4), request.expMonth(),
                expYear, fingerprint(pan), bin.cardType(), bin.cardRegion());
    }

    /** What payments is allowed to know about a card: never the number itself. */
    @GetMapping("/tokens/{token}")
    TokenizeResponse describe(@PathVariable String token) {
        StoredCard card = tokens.get(token);
        if (card == null) {
            throw ApiException.notFound("invalid_token", "Unknown or expired card token");
        }
        BinTable.BinInfo bin = BinTable.lookup(card.pan());
        return new TokenizeResponse(token, TestCards.brand(card.pan()), card.pan().substring(card.pan().length() - 4),
                card.expMonth(), card.expYear(), fingerprint(card.pan()), bin.cardType(), bin.cardRegion());
    }

    @PostMapping("/authorize")
    AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest request) throws InterruptedException {
        return decide(request.token(), request.amount(), request.currency(), request.channel(),
                Boolean.TRUE.equals(request.offSession()));
    }

    /**
     * Checks that a card can be used later without charging it (saving a card). The same rules as a payment apply, so
     * a card that needs 3-D Secure asks for it here, once, and the saved card can then be charged without the customer.
     */
    @PostMapping("/verify")
    AuthorizeResponse verify(@Valid @RequestBody VerifyRequest request) throws InterruptedException {
        return decide(request.token(), 0, "EUR", request.channel(), false);
    }

    private AuthorizeResponse decide(String token, long amount, String currency, String channel, boolean offSession)
            throws InterruptedException {
        StoredCard card = tokens.get(token);
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
        // Mail/telephone orders are outside strong customer authentication: the merchant keys the card in, so
        // there is no cardholder present to answer a challenge and the issuer is not allowed to ask for one.
        boolean moto = "moto".equals(channel);
        if (offSession && outcome.behaviour() == TestCards.Behaviour.THREE_DS_ALWAYS) {
            // Nobody is there to answer a challenge, so this card cannot be charged until the customer returns.
            return declined("authentication_required", "Your bank requires authentication for this payment.");
        }
        // The plain 3-D Secure card is challenged while the customer is present; once they authenticated, saved
        // copies of the card can be charged off-session.
        boolean asksForChallenge = outcome.behaviour() == TestCards.Behaviour.THREE_DS
                || outcome.behaviour() == TestCards.Behaviour.THREE_DS_ALWAYS;
        if (asksForChallenge && !moto && !offSession) {
            challenges.put(ref, new PendingChallenge(ref, amount, currency));
            return new AuthorizeResponse("requires_action", ref, null, "3-D Secure authentication required");
        }
        authorizations.put(ref, amount);
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

    /**
     * Takes money from a held authorisation, in full or in part; the rest is released. Asking again for the
     * same amount succeeds again (a retry after a lost answer), a different amount is refused.
     */
    @PostMapping("/capture")
    AuthorizeResponse capture(@Valid @RequestBody RefundRequest request) {
        String ref = request.acquirerRef();
        synchronized (captured) {
            Long held = authorizations.get(ref);
            if (held == null || voided.contains(ref)) {
                throw ApiException.notFound("authorization_not_found", "Unknown or released authorization");
            }
            Long already = captured.get(ref);
            if (already != null) {
                if (already == request.amount()) {
                    return new AuthorizeResponse("approved", ref, null, null);
                }
                throw ApiException.conflict("already_captured", "This authorization was already captured", null);
            }
            if (request.amount() > held) {
                throw ApiException.invalidRequest("capture_exceeds_authorization",
                        "Capture is larger than the authorised amount", "amount");
            }
            captured.put(ref, request.amount());
            // From now on only what was captured can be refunded.
            authorizations.put(ref, request.amount());
        }
        return new AuthorizeResponse("approved", ref, null, null);
    }

    /** Releases a held authorisation so the cardholder's available balance is restored. Repeating it is harmless. */
    @PostMapping("/void")
    AuthorizeResponse voidAuthorization(@Valid @RequestBody VoidRequest request) {
        String ref = request.acquirerRef();
        synchronized (captured) {
            if (!authorizations.containsKey(ref)) {
                throw ApiException.notFound("authorization_not_found", "Unknown authorization");
            }
            if (captured.containsKey(ref)) {
                throw ApiException.conflict("already_captured", "A captured authorization cannot be released", null);
            }
            voided.add(ref);
        }
        return new AuthorizeResponse("approved", ref, null, null);
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
