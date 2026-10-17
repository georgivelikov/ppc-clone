package dev.cutpay.payments.payment;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.money.Money;
import dev.cutpay.money.SupportedCurrencies;
import dev.cutpay.payments.acquirer.AcquirerClient;
import dev.cutpay.payments.acquirer.AcquirerClient.AuthResult;
import dev.cutpay.payments.acquirer.AcquirerClient.CardInfo;
import dev.cutpay.payments.config.KafkaTopicsConfig;
import dev.cutpay.payments.domain.PaymentAttempt;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PROCESSING_ERROR_MESSAGE = "We couldn't process your card. Please try again.";

    private final PaymentIntentRepository intents;
    private final PaymentAttemptRepository attempts;
    private final RefundRepository refunds;
    private final AcquirerClient acquirer;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public PaymentService(PaymentIntentRepository intents, PaymentAttemptRepository attempts, RefundRepository refunds,
                          AcquirerClient acquirer, OutboxWriter outbox, TransactionTemplate tx) {
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
        if (!SupportedCurrencies.isSupported(request.currency())) {
            throw ApiException.invalidRequest("unsupported_currency", "Currency is not supported", "currency");
        }
        Money money = Money.ofMinor(request.amount(), request.currency().toUpperCase(Locale.ROOT));
        String id = Ids.generate("pay");
        var intent = new PaymentIntent(id, id + "_secret_" + randomSecret(), merchantId, request.merchantName(), livemode,
                money, request.description(), request.customerEmail(), request.metadata(), request.returnUrl());
        intents.save(intent);
        publish("PaymentIntentCreated", intent);
        return intent;
    }

    /** {@code claimedMode} is the mode an API key is limited to; null for dashboard sessions, which see both. */
    @Transactional(readOnly = true)
    public PaymentView get(String merchantId, Boolean claimedMode, String id) {
        PaymentIntent intent = find(merchantId, claimedMode, id)
                .orElseThrow(() -> ApiException.notFound("payment_not_found", "No such payment"));
        return PaymentView.of(intent, attempts.findByPaymentIntentIdOrderByCreatedAtAsc(id),
                refunds.findByPaymentIntentIdOrderByCreatedAtAsc(id));
    }

    private java.util.Optional<PaymentIntent> find(String merchantId, Boolean claimedMode, String id) {
        return claimedMode == null ? intents.findByIdAndMerchantId(id, merchantId)
                : intents.findByIdAndMerchantIdAndLivemode(id, merchantId, claimedMode);
    }

    public record Filter(String status, String query, Instant from, Instant to, Long minAmount, Long maxAmount,
                         String startingAfter, int limit) {
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
                .map(p -> PaymentView.of(p, attempts.findByPaymentIntentIdOrderByCreatedAtAsc(p.getId()), java.util.List.of()))
                .toList();
        return new PaymentList(data, hasMore);
    }

    @Transactional
    public PaymentView cancel(String merchantId, Boolean claimedMode, String id) {
        PaymentIntent intent = find(merchantId, claimedMode, id)
                .orElseThrow(() -> ApiException.notFound("payment_not_found", "No such payment"));
        intent.cancel();
        publish("PaymentCanceled", intent);
        return PaymentView.of(intent, attempts.findByPaymentIntentIdOrderByCreatedAtAsc(id),
                refunds.findByPaymentIntentIdOrderByCreatedAtAsc(id));
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
                intent.getFailureMessage(), next);
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

        String attemptId;
        try {
            attemptId = tx.execute(status -> prepare(clientSecret, request.email(), card));
        } catch (OptimisticLockingFailureException e) {
            throw ApiException.conflict("payment_not_confirmable", "This payment is already being processed", null);
        }

        AuthResult result = acquirer.authorize(request.token(), snapshot.getAmount(), snapshot.getCurrency(),
                snapshot.getId());
        tx.execute(status -> {
            settle(snapshot.getId(), attemptId, result);
            return null;
        });
        return session(clientSecret);
    }

    private String prepare(String clientSecret, String email, CardInfo card) {
        PaymentIntent intent = intents.findByClientSecret(clientSecret).orElseThrow();
        intent.startProcessing(email);
        var attempt = new PaymentAttempt(Ids.generate("att"), intent.getId(), card.brand(), card.last4(),
                card.expMonth(), card.expYear(), card.fingerprint(), card.cardType(), card.cardRegion());
        attempts.save(attempt);
        intents.save(intent);
        return attempt.getId();
    }

    private void settle(String intentId, String attemptId, AuthResult result) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow();
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        switch (result) {
            case AuthResult.Approved approved -> {
                attempt.succeeded(approved.acquirerRef());
                intent.succeed();
                publish("PaymentSucceeded", intent, attempt);
            }
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
            attempt.succeeded(acquirerRef);
            intent.resumeProcessing();
            intent.succeed();
            publish("PaymentSucceeded", intent, attempt);
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
        if (attempt != null) {
            payload.put("card_brand", attempt.getCardBrand());
            payload.put("card_type", attempt.getCardType());
            payload.put("card_region", attempt.getCardRegion());
        }
        outbox.write(KafkaTopicsConfig.PAYMENT_TOPIC, eventType, intent.getId(), intent.getMerchantId(),
                intent.isLivemode(), payload);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
