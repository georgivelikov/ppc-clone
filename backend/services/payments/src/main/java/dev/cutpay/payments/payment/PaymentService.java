package dev.cutpay.payments.payment;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.money.Money;
import dev.cutpay.money.SupportedCurrencies;
import dev.cutpay.payments.acquirer.AcquirerClient;
import dev.cutpay.payments.acquirer.AcquirerClient.AuthResult;
import dev.cutpay.payments.acquirer.AcquirerClient.CardInfo;
import dev.cutpay.payments.config.KafkaTopicsConfig;
import dev.cutpay.payments.customer.CustomerService;
import dev.cutpay.payments.domain.PaymentAttempt;
import dev.cutpay.payments.domain.PaymentMethod;
import dev.cutpay.payments.domain.PaymentIntent;
import dev.cutpay.payments.domain.PaymentStatus;
import dev.cutpay.payments.domain.Repositories.PaymentAttemptRepository;
import dev.cutpay.payments.domain.Repositories.PaymentIntentRepository;
import dev.cutpay.payments.domain.Repositories.RefundRepository;
import dev.cutpay.payments.payment.PaymentDtos.ConfirmRequest;
import dev.cutpay.payments.payment.PaymentDtos.CreatePaymentRequest;
import dev.cutpay.payments.payment.PaymentDtos.NextAction;
import dev.cutpay.payments.payment.PaymentDtos.PaymentList;
import dev.cutpay.payments.payment.PaymentDtos.PaymentView;
import dev.cutpay.payments.payment.PaymentDtos.SessionView;
import dev.cutpay.payments.payment.PaymentDtos.SummaryView;
import dev.cutpay.payments.payment.PaymentDtos.VolumeView;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import jakarta.persistence.criteria.Predicate;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PROCESSING_ERROR_MESSAGE = "We couldn't process your card. Please try again.";

    private final PaymentIntentRepository intents;
    private final PaymentAttemptRepository attempts;
    private final RefundRepository refunds;
    private final AcquirerClient acquirer;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final PaymentStreams streams;
    private final Clock clock;
    private final CustomerService customers;
    private final Duration authorizationValidity;

    public PaymentService(PaymentIntentRepository intents, PaymentAttemptRepository attempts, RefundRepository refunds,
                          AcquirerClient acquirer, OutboxWriter outbox, TransactionTemplate tx,
                          PaymentStreams streams, Clock clock, CustomerService customers,
                          @Value("${cutpay.payments.authorization-validity:P7D}") Duration authorizationValidity) {
        this.streams = streams;
        this.clock = clock;
        this.customers = customers;
        this.authorizationValidity = authorizationValidity;
        this.intents = intents;
        this.attempts = attempts;
        this.refunds = refunds;
        this.acquirer = acquirer;
        this.outbox = outbox;
        this.tx = tx;
    }

    // --- merchant side ---------------------------------------------------------------------------

    @Transactional
    public PaymentIntent create(String merchantId, boolean livemode, CreatePaymentRequest request) {
        return create(merchantId, livemode, request, PaymentIntent.CHANNEL_ONLINE);
    }

    @Transactional
    public PaymentIntent create(String merchantId, boolean livemode, CreatePaymentRequest request, String channel) {
        if (!SupportedCurrencies.isSupported(request.currency())) {
            throw ApiException.invalidRequest("unsupported_currency", "Currency is not supported", "currency");
        }
        Money money = Money.ofMinor(request.amount(), request.currency().toUpperCase(Locale.ROOT));
        String id = Ids.generate("pay");
        String email = request.customerEmail();
        String customerId = null;
        String methodId = null;
        if (request.customer() != null) {
            var customer = customers.find(merchantId, livemode, request.customer());
            customerId = customer.getId();
            email = email != null ? email : customer.getEmail();
            if (request.paymentMethod() != null || request.chargesSavedCard()) {
                methodId = customers.chargeable(merchantId, livemode, customerId, request.paymentMethod()).getId();
            }
        } else if (request.paymentMethod() != null || request.chargesSavedCard() || request.setupFutureUsage() != null) {
            throw ApiException.invalidRequest("customer_required",
                    "A saved card, off_session and setup_future_usage need a customer", "customer");
        }
        var intent = new PaymentIntent(id, id + "_secret_" + randomSecret(), merchantId, request.merchantName(), livemode,
                money, request.description(), email, request.metadata(), request.returnUrl(), channel,
                request.captureMethod() == null ? PaymentIntent.CAPTURE_AUTOMATIC : request.captureMethod());
        intent.forCustomer(customerId, methodId, request.chargesSavedCard(), request.setupFutureUsage());
        intents.save(intent);
        publish("PaymentIntentCreated", intent);
        return intent;
    }

    /**
     * Creates the payment and, for an off-session one, charges the saved card straight away without the customer.
     * The charge happens after the payment is stored, outside its transaction, like any card payment.
     */
    public PaymentIntent createAndCharge(String merchantId, boolean livemode, CreatePaymentRequest request) {
        PaymentIntent intent = tx.execute(status -> create(merchantId, livemode, request, PaymentIntent.CHANNEL_ONLINE));
        if (intent.isOffSession()) {
            chargeSavedCard(intent);
        }
        return intents.findById(intent.getId()).orElseThrow();
    }

    private void chargeSavedCard(PaymentIntent intent) {
        PaymentMethod method = customers.method(intent.getPaymentMethodId());
        var card = new CardInfo(method.getAcquirerToken(), method.getCardBrand(), method.getCardLast4(),
                method.getCardExpMonth(), method.getCardExpYear(), method.getCardFingerprint(), method.getCardType(),
                method.getCardRegion());
        execute(intent, method.getAcquirerToken(), card, intent.getCustomerEmail());
    }

    /** {@code claimedMode} is the mode an API key is limited to; null for dashboard sessions, which see both. */
    @Transactional(readOnly = true)
    public PaymentView get(String merchantId, Boolean claimedMode, String id, Set<String> expand) {
        PaymentIntent intent = find(merchantId, claimedMode, id)
                .orElseThrow(() -> ApiException.notFound("payment_not_found", "No such payment"));
        return PaymentView.of(intent, attempts.findByPaymentIntentIdOrderByCreatedAtAsc(id),
                refunds.findByPaymentIntentIdOrderByCreatedAtAsc(id), expand.contains(EXPAND_LATEST_ATTEMPT));
    }

    private java.util.Optional<PaymentIntent> find(String merchantId, Boolean claimedMode, String id) {
        return claimedMode == null ? intents.findByIdAndMerchantId(id, merchantId)
                : intents.findByIdAndMerchantIdAndLivemode(id, merchantId, claimedMode);
    }

    /** The only expandable field so far: {@code ?expand[]=latest_attempt} replaces the attempt id by the attempt. */
    public static final String EXPAND_LATEST_ATTEMPT = "latest_attempt";

    public record Filter(String status, String query, Instant from, Instant to, Long minAmount, Long maxAmount,
                         String startingAfter, int limit, Set<String> expand, String customer) {
    }

    @Transactional(readOnly = true)
    public PaymentList list(String merchantId, boolean livemode, Filter filter) {
        Specification<PaymentIntent> spec = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("merchantId"), merchantId));
            predicates.add(cb.equal(root.get("livemode"), livemode));
            if (filter.status() != null && !filter.status().isBlank()) {
                try {
                    predicates.add(cb.equal(root.get("status"), PaymentStatus.fromWire(filter.status())));
                } catch (IllegalArgumentException e) {
                    throw ApiException.invalidRequest("invalid_status", "Unknown payment status", "status");
                }
            }
            if (filter.query() != null && !filter.query().isBlank()) {
                String like = "%" + filter.query().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("id")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("customerEmail"), "")), like)));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filter.to()));
            }
            if (filter.customer() != null && !filter.customer().isBlank()) {
                predicates.add(cb.equal(root.get("customerId"), filter.customer()));
            }
            if (filter.minAmount() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), filter.minAmount()));
            }
            if (filter.maxAmount() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), filter.maxAmount()));
            }
            if (filter.startingAfter() != null && !filter.startingAfter().isBlank()) {
                // IDs are ULIDs, so ordering by id is ordering by creation time.
                predicates.add(cb.lessThan(root.get("id"), filter.startingAfter()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        int limit = Math.max(1, Math.min(filter.limit(), 100));
        var page = intents.findBy(spec, q -> q.sortBy(Sort.by(Sort.Direction.DESC, "id")).limit(limit + 1).all());
        boolean hasMore = page.size() > limit;
        var data = page.stream().limit(limit)
                .map(p -> PaymentView.of(p, attempts.findByPaymentIntentIdOrderByCreatedAtAsc(p.getId()), java.util.List.of(),
                        filter.expand().contains(EXPAND_LATEST_ATTEMPT)))
                .toList();
        return new PaymentList("list", data, hasMore);
    }

    /**
     * Cancels an unpaid payment, or releases the hold of an authorised one (the acquirer is told first, so
     * the cardholder's money is not left reserved if the cancel succeeds only on our side).
     */
    public PaymentView cancel(String merchantId, Boolean claimedMode, String id) {
        PaymentIntent snapshot = find(merchantId, claimedMode, id)
                .orElseThrow(() -> ApiException.notFound("payment_not_found", "No such payment"));
        if (snapshot.getStatus() == PaymentStatus.REQUIRES_CAPTURE) {
            requireDone(acquirer.release(heldReference(id)), "release");
        }
        return tx.execute(status -> {
            PaymentIntent intent = intents.lockById(id).orElseThrow();
            intent.cancel();
            attempts.findFirstByPaymentIntentIdOrderByCreatedAtDesc(id)
                    .filter(a -> "authorized".equals(a.getStatus())).ifPresent(PaymentAttempt::released);
            publish("PaymentCanceled", intent);
            return PaymentView.of(intent, attempts.findByPaymentIntentIdOrderByCreatedAtAsc(id),
                    refunds.findByPaymentIntentIdOrderByCreatedAtAsc(id));
        });
    }

    /**
     * Takes money from an authorised payment. Omitting the amount takes everything that was held; a smaller
     * amount releases the rest. The acquirer is called outside any database transaction, like a payment.
     */
    public PaymentView capture(String merchantId, Boolean claimedMode, String id, Long amountToCapture) {
        PaymentIntent snapshot = find(merchantId, claimedMode, id)
                .orElseThrow(() -> ApiException.notFound("payment_not_found", "No such payment"));
        if (snapshot.getStatus() != PaymentStatus.REQUIRES_CAPTURE) {
            throw ApiException.conflict("invalid_state", "Only an authorised payment can be captured", null);
        }
        long amount = amountToCapture != null ? amountToCapture : snapshot.getAmount();
        if (amount < 1 || amount > snapshot.getAmount()) {
            throw ApiException.invalidRequest("invalid_capture_amount",
                    "Capture must be between 1 and the authorised %d".formatted(snapshot.getAmount()),
                    "amount_to_capture");
        }
        requireDone(acquirer.capture(heldReference(id), amount), "capture");
        return tx.execute(status -> {
            PaymentIntent intent = intents.lockById(id).orElseThrow();
            if (intent.getStatus() != PaymentStatus.REQUIRES_CAPTURE) {
                throw ApiException.conflict("invalid_state", "This payment was captured or released already", null);
            }
            PaymentAttempt attempt = attempts.findFirstByPaymentIntentIdOrderByCreatedAtDesc(id).orElseThrow();
            intent.capture(amount);
            attempt.captured();
            publish("PaymentSucceeded", intent, attempt);
            return PaymentView.of(intent, attempts.findByPaymentIntentIdOrderByCreatedAtAsc(id),
                    refunds.findByPaymentIntentIdOrderByCreatedAtAsc(id));
        });
    }

    /**
     * Releases authorisations nobody captured in time (7 days by default). One at a time and independent, so a
     * single payment the acquirer cannot reach does not hold up the others; it is simply tried again.
     *
     * @return how many were released
     */
    public int expireUncaptured(int limit) {
        int released = 0;
        for (String id : intents.findIdsDueForExpiry(clock.instant(), PageRequest.of(0, limit))) {
            try {
                String reference = heldReference(id);
                if (!(acquirer.release(reference) instanceof AcquirerClient.HoldResult.Done)) {
                    log.warn("Could not release the hold of {} at the acquirer, will retry", id);
                    continue;
                }
                released += tx.execute(status -> {
                    PaymentIntent intent = intents.lockById(id).orElseThrow();
                    if (intent.getStatus() != PaymentStatus.REQUIRES_CAPTURE) {
                        return 0;
                    }
                    intent.expire();
                    attempts.findFirstByPaymentIntentIdOrderByCreatedAtDesc(id)
                            .filter(a -> "authorized".equals(a.getStatus())).ifPresent(PaymentAttempt::released);
                    publish("PaymentCanceled", intent);
                    return 1;
                });
            } catch (RuntimeException e) {
                log.warn("Could not expire the authorisation of {}", id, e);
            }
        }
        return released;
    }

    private String heldReference(String intentId) {
        return attempts.findFirstByPaymentIntentIdOrderByCreatedAtDesc(intentId)
                .filter(a -> "authorized".equals(a.getStatus())).map(PaymentAttempt::getAcquirerRef)
                .orElseThrow(() -> ApiException.conflict("invalid_state", "This payment holds no authorisation", null));
    }

    private static void requireDone(AcquirerClient.HoldResult result, String what) {
        switch (result) {
            case AcquirerClient.HoldResult.Done done -> { }
            case AcquirerClient.HoldResult.Rejected rejected -> throw ApiException.conflict(
                    "acquirer_rejected", "The card network refused the %s (%s)".formatted(what, rejected.code()), null);
            case AcquirerClient.HoldResult.Unavailable unavailable -> throw new ApiException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, "api_error", "acquirer_unavailable",
                    "The card network is unavailable, please try again", null);
        }
    }

    @Transactional(readOnly = true)
    public SummaryView todaysVolume(String merchantId, boolean livemode) {
        Instant since = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        var rows = intents.volumeSince(merchantId, livemode, since).stream()
                .map(r -> new VolumeView(r.currency(), r.count(), r.volume())).toList();
        return new SummaryView(since.toString(), rows);
    }

    // --- hosted checkout -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SessionView session(String clientSecret) {
        PaymentIntent intent = intents.findByClientSecret(clientSecret)
                .orElseThrow(() -> ApiException.notFound("session_not_found", "This payment link is not valid"));
        NextAction next = null;
        if (intent.getStatus() == PaymentStatus.REQUIRES_ACTION) {
            next = attempts.findFirstByPaymentIntentIdOrderByCreatedAtDesc(intent.getId())
                    .map(a -> new NextAction("three_ds", a.getAcquirerRef())).orElse(null);
        }
        return new SessionView(intent.getId(), intent.getStatus().wire(), intent.getAmount(), intent.getCurrency(),
                intent.getDescription(), intent.getMerchantName(), intent.getReturnUrl(), intent.getFailureCode(),
                intent.getFailureMessage(), next, intent.getSetupFutureUsage() != null);
    }

    /**
     * Pays with a card token. The database is never held open during the call to the acquirer: first a
     * short transaction moves the payment to {@code processing}, then the remote call happens, then a second
     * transaction records the outcome. A second confirm in between is rejected because the status moved.
     */
    public SessionView confirm(String clientSecret, ConfirmRequest request) {
        PaymentIntent snapshot = intents.findByClientSecret(clientSecret)
                .orElseThrow(() -> ApiException.notFound("session_not_found", "This payment link is not valid"));
        if (snapshot.getStatus() != PaymentStatus.REQUIRES_PAYMENT_METHOD) {
            throw ApiException.conflict("payment_not_confirmable", "This payment can no longer be paid", null);
        }
        CardInfo card = acquirer.describeToken(request.token());
        execute(snapshot, request.token(), card, request.email());
        return session(clientSecret);
    }

    /** One try to take the money: moves to processing, asks the card network, records the answer. */
    private void execute(PaymentIntent snapshot, String token, CardInfo card, String email) {
        String attemptId;
        try {
            attemptId = tx.execute(status -> prepare(snapshot.getId(), email, card, token));
        } catch (OptimisticLockingFailureException e) {
            throw ApiException.conflict("payment_not_confirmable", "This payment is already being processed", null);
        }

        AuthResult result = acquirer.authorize(token, snapshot.getAmount(), snapshot.getCurrency(),
                snapshot.getId(), snapshot.getChannel(), snapshot.isOffSession());
        tx.execute(status -> {
            settle(snapshot.getId(), attemptId, result);
            return null;
        });
    }

    private String prepare(String intentId, String email, CardInfo card, String token) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow();
        intent.startProcessing(email);
        var attempt = new PaymentAttempt(Ids.generate("att"), intent.getId(), card.brand(), card.last4(),
                card.expMonth(), card.expYear(), card.fingerprint(), card.cardType(), card.cardRegion())
                .withToken(token);
        attempts.save(attempt);
        intents.save(intent);
        return attempt.getId();
    }

    private void settle(String intentId, String attemptId, AuthResult result) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow();
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        switch (result) {
            case AuthResult.Approved approved -> approve(intent, attempt, approved.acquirerRef());
            case AuthResult.RequiresAction action -> {
                attempt.requiresAction(action.acquirerRef());
                intent.requireAction();
                publish("PaymentRequiresAction", intent);
            }
            case AuthResult.Declined declined -> {
                attempt.failed(declined.code());
                intent.fail(declined.code(), declined.message());
                publish("PaymentFailed", intent);
            }
            case AuthResult.Unavailable unavailable -> {
                attempt.failed("processing_error");
                intent.fail("processing_error", PROCESSING_ERROR_MESSAGE);
                publish("PaymentFailed", intent);
            }
        }
    }

    /** The issuer approved: take the money now, or only hold it when the merchant will capture later. */
    private void approve(PaymentIntent intent, PaymentAttempt attempt, String acquirerRef) {
        if (intent.getSetupFutureUsage() != null && intent.getCustomerId() != null && attempt.getAcquirerToken() != null) {
            // The customer agreed to save this card for later payments (subscriptions, repeat orders).
            var card = new CardInfo(attempt.getAcquirerToken(), attempt.getCardBrand(), attempt.getCardLast4(),
                    attempt.getCardExpMonth(), attempt.getCardExpYear(), attempt.getCardFingerprint(),
                    attempt.getCardType(), attempt.getCardRegion());
            PaymentMethod saved = customers.saveCard(intent.getMerchantId(), intent.getCustomerId(), card,
                    attempt.getAcquirerToken());
            intent.usePaymentMethod(saved.getId());
        }
        if (intent.isManualCapture()) {
            attempt.authorized(acquirerRef);
            intent.authorize(clock.instant().plus(authorizationValidity));
            publish("PaymentAuthorized", intent, attempt);
        } else {
            attempt.succeeded(acquirerRef);
            intent.succeed();
            publish("PaymentSucceeded", intent, attempt);
        }
    }

    /** Called by the acquirer once the customer answered the 3DS challenge. Safe to receive twice. */
    @Transactional
    public void completeThreeDs(String acquirerRef, boolean approved, String declineCode, String message) {
        PaymentAttempt attempt = attempts.findFirstByAcquirerRef(acquirerRef)
                .orElseThrow(() -> ApiException.notFound("attempt_not_found", "Unknown authorisation"));
        PaymentIntent intent = intents.lockById(attempt.getPaymentIntentId()).orElseThrow();
        if (intent.getStatus() != PaymentStatus.REQUIRES_ACTION) {
            return;
        }
        if (approved) {
            intent.resumeProcessing();
            approve(intent, attempt, acquirerRef);
        } else {
            attempt.failed(declineCode == null || declineCode.isBlank() ? "authentication_failed" : declineCode);
            intent.fail("authentication_failed", message == null || message.isBlank()
                    ? "3-D Secure authentication failed." : message);
            publish("PaymentFailed", intent);
        }
    }

    // --- events ----------------------------------------------------------------------------------

    private void publish(String eventType, PaymentIntent intent) {
        publish(eventType, intent, null);
    }

    /** {@code attempt} is the card attempt that settled the intent; its card details feed the ledger's fees. */
    private void publish(String eventType, PaymentIntent intent, PaymentAttempt attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_id", intent.getId());
        payload.put("status", intent.getStatus().wire());
        payload.put("amount", intent.getAmount());
        payload.put("currency", intent.getCurrency());
        payload.put("customer_email", intent.getCustomerEmail());
        payload.put("failure_code", intent.getFailureCode());
        payload.put("channel", intent.getChannel());
        payload.put("metadata", intent.getMetadata());
        if (attempt != null) {
            payload.put("card_brand", attempt.getCardBrand());
            payload.put("card_type", attempt.getCardType());
            payload.put("card_region", attempt.getCardRegion());
        }
        outbox.write(KafkaTopicsConfig.PAYMENT_TOPIC, eventType, intent.getId(), intent.getMerchantId(),
                intent.isLivemode(), payload);
        // Screens watching this payment (the "charge with QR" page) hear about it as soon as it commits.
        streams.publishAfterCommit(intent.getId(), intent.getStatus().wire(), intent.getFailureCode());
    }

    private static String randomSecret() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
