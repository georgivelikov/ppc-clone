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
import dev.cutpay.payments.domain.PaymentTransfer;
import dev.cutpay.payments.domain.Repositories.PaymentTransferRepository;
import dev.cutpay.payments.identity.IdentityClient;
import dev.cutpay.payments.payment.PaymentDtos.TransferInput;
import dev.cutpay.payments.payment.PaymentDtos.TransferView;
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
import java.util.List;
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
    private final PaymentTransferRepository transfers;
    private final IdentityClient identity;
    private final Duration authorizationValidity;
    private final PaymentMethodSettings methodSettings;

    public PaymentService(PaymentIntentRepository intents, PaymentAttemptRepository attempts, RefundRepository refunds,
                          AcquirerClient acquirer, OutboxWriter outbox, TransactionTemplate tx,
                          PaymentStreams streams, Clock clock, CustomerService customers,
                          PaymentTransferRepository transfers, IdentityClient identity,
                          PaymentMethodSettings methodSettings,
                          @Value("${cutpay.payments.authorization-validity:P7D}") Duration authorizationValidity) {
        this.methodSettings = methodSettings;
        this.streams = streams;
        this.clock = clock;
        this.customers = customers;
        this.transfers = transfers;
        this.identity = identity;
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
        intent.setLocale(request.locale());
        List<PaymentTransfer> planned = planTransfers(merchantId, livemode, id, money.minor(), money.currency().getCurrencyCode(),
                request);
        if (!planned.isEmpty() && request.applicationFeeAmount() != null) {
            intent.setApplicationFeeAmount(request.applicationFeeAmount());
        }
        intents.save(intent);
        transfers.saveAll(planned);
        publish("PaymentIntentCreated", intent);
        return intent;
    }

    private static final int MAX_TRANSFERS = 10;

    /**
     * Works out how a split payment is shared. Either each destination is given an amount and the platform keeps the
     * rest, or one destination is given no amount and {@code application_fee_amount} says what the platform keeps:
     * the destination then gets everything else. The platform pays the processing fee out of its own part.
     */
    private List<PaymentTransfer> planTransfers(String merchantId, boolean livemode, String paymentId, long amount,
                                                String currency, CreatePaymentRequest request) {
        List<TransferInput> inputs = request.transferData() == null ? List.of() : request.transferData();
        if (inputs.isEmpty()) {
            if (request.applicationFeeAmount() != null) {
                throw ApiException.invalidRequest("destination_required",
                        "An application fee needs a destination in transfer_data", "application_fee_amount");
            }
            return List.of();
        }
        if (inputs.size() > MAX_TRANSFERS) {
            throw ApiException.invalidRequest("too_many_transfers", "A payment can be split between 10 accounts at most",
                    "transfer_data");
        }
        if (PaymentIntent.CAPTURE_MANUAL.equals(request.captureMethod())) {
            throw ApiException.invalidRequest("transfers_need_automatic_capture",
                    "A payment that is captured later cannot be split", "capture_method");
        }
        boolean byFee = request.applicationFeeAmount() != null;
        if (byFee && (inputs.size() != 1 || inputs.get(0).amount() != null)) {
            throw ApiException.invalidRequest("ambiguous_split",
                    "Give either an amount for each destination, or one destination without an amount and an application fee",
                    "application_fee_amount");
        }
        if (!byFee && inputs.stream().anyMatch(t -> t.amount() == null)) {
            throw ApiException.invalidRequest("amount_required", "Every destination needs an amount", "transfer_data");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<PaymentTransfer> planned = new ArrayList<>();
        long sent = 0;
        for (TransferInput input : inputs) {
            if (!seen.add(input.destination())) {
                throw ApiException.invalidRequest("duplicate_destination", "Each account can appear once", "transfer_data");
            }
            var account = identity.connectedAccount(merchantId, input.destination());
            if (!account.active()) {
                throw ApiException.invalidRequest("connected_account_disabled", "That account is disabled", "transfer_data");
            }
            if (account.livemode() != livemode) {
                throw ApiException.invalidRequest("connected_account_not_found",
                        "No connected account %s on this platform".formatted(input.destination()), "transfer_data");
            }
            if (!account.currency().equals(currency)) {
                throw ApiException.invalidRequest("currency_mismatch",
                        "That account is paid out in %s, the payment is in %s".formatted(account.currency(), currency),
                        "transfer_data");
            }
            long share = byFee ? amount - request.applicationFeeAmount() : input.amount();
            if (share < 1) {
                throw ApiException.invalidRequest("fee_too_large", "The application fee leaves nothing for the account",
                        "application_fee_amount");
            }
            sent += share;
            planned.add(new PaymentTransfer(Ids.generate("tr"), paymentId, input.destination(), share));
        }
        if (sent > amount) {
            throw ApiException.invalidRequest("transfers_exceed_payment", "The transfers add up to more than the payment",
                    "transfer_data");
        }
        return planned;
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
                        refunds.findByPaymentIntentIdOrderByCreatedAtAsc(id), expand.contains(EXPAND_LATEST_ATTEMPT))
                .withTransfers(transfers.findByPaymentIntentIdOrderByIdAsc(id).stream()
                        .map(t -> new TransferView(t.getDestination(), t.getAmount())).toList());
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

    /** What a service acting for the checkout (the BNPL aggregator) needs to know about a payment. */
    public record CheckoutFacts(String paymentId, String merchantId, boolean livemode, String merchantName, String status,
                                long amount, String currency, String description, String customerEmail, String captureMethod,
                                boolean split) {
    }

    @Transactional(readOnly = true)
    public CheckoutFacts checkoutFacts(String clientSecret) {
        PaymentIntent intent = intents.findByClientSecret(clientSecret)
                .orElseThrow(() -> ApiException.notFound("session_not_found", "This payment link is not valid"));
        return new CheckoutFacts(intent.getId(), intent.getMerchantId(), intent.isLivemode(), intent.getMerchantName(),
                intent.getStatus().wire(), intent.getAmount(), intent.getCurrency(), intent.getDescription(),
                intent.getCustomerEmail(), intent.getCaptureMethod(),
                !transfers.findByPaymentIntentIdOrderByIdAsc(intent.getId()).isEmpty());
    }

    /** The ways of paying this checkout offers. */
    @Transactional(readOnly = true)
    public List<String> methods(String clientSecret) {
        PaymentIntent intent = intents.findByClientSecret(clientSecret)
                .orElseThrow(() -> ApiException.notFound("session_not_found", "This payment link is not valid"));
        return PaymentMethodRules.available(intent.getCurrency(), intent.getCaptureMethod(),
                methodSettings.disabled(intent.getMerchantId()));
    }

    @Transactional(readOnly = true)
    public SessionView session(String clientSecret) {
        PaymentIntent intent = intents.findByClientSecret(clientSecret)
                .orElseThrow(() -> ApiException.notFound("session_not_found", "This payment link is not valid"));
        NextAction next = null;
        if (intent.getStatus() == PaymentStatus.REQUIRES_ACTION) {
            next = attempts.findFirstByPaymentIntentIdOrderByCreatedAtDesc(intent.getId())
                    .map(a -> new NextAction("blik".equals(a.getMethodType()) ? "blik" : "three_ds", a.getAcquirerRef()))
                    .orElse(null);
        }
        return new SessionView(intent.getId(), intent.getStatus().wire(), intent.getAmount(), intent.getCurrency(),
                intent.getDescription(), intent.getMerchantName(), intent.getReturnUrl(), intent.getFailureCode(),
                intent.getFailureMessage(), next, intent.getSetupFutureUsage() != null, intent.getLocale());
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
        if (request.isBlik()) {
            requireEnabled(snapshot, PaymentMethodRules.BLIK);
            confirmBlik(snapshot, request);
            return session(clientSecret);
        }
        if (request.token() == null || request.token().isBlank()) {
            throw ApiException.invalidRequest("token_required", "A card token is required", "token");
        }
        CardInfo card = acquirer.describeToken(request.token());
        if (card.walletType() != null) {
            requireEnabled(snapshot, card.walletType());
        }
        execute(snapshot, request.token(), card, request.email());
        return session(clientSecret);
    }

    private void requireEnabled(PaymentIntent intent, String method) {
        if (!methodSettings.enabled(intent.getMerchantId(), method)) {
            throw ApiException.invalidRequest("method_disabled", "This merchant does not accept that way of paying", "method");
        }
    }

    /**
     * Pays with a BLIK code. The bank answers in two steps: it accepts the code at once, then the customer confirms in their
     * banking app, and that answer arrives later as a call back ({@link #completeBlik}). A code that is not a valid code
     * leaves the payment untouched, so the customer can type another.
     */
    private void confirmBlik(PaymentIntent snapshot, ConfirmRequest request) {
        if (!"PLN".equals(snapshot.getCurrency())) {
            throw ApiException.invalidRequest("blik_pln_only", "BLIK is only available for payments in PLN", "method");
        }
        if (snapshot.isManualCapture()) {
            throw ApiException.invalidRequest("blik_automatic_capture_only", "A BLIK payment cannot be captured later",
                    "method");
        }
        String code = request.blikCode() == null ? "" : request.blikCode().trim();
        if (!code.matches("[0-9]{6}")) {
            throw ApiException.invalidRequest("invalid_blik_code", "A BLIK code has six digits", "blik_code");
        }
        AcquirerClient.BlikResult result = acquirer.blikCharge(code, snapshot.getAmount(), snapshot.getCurrency(),
                snapshot.getId());
        if (result instanceof AcquirerClient.BlikResult.InvalidCode invalid) {
            throw ApiException.invalidRequest("invalid_blik_code", invalid.message(), "blik_code");
        }
        if (result instanceof AcquirerClient.BlikResult.Unavailable) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "api_error", "acquirer_unavailable",
                    "BLIK is unavailable, please try again", null);
        }
        try {
            tx.execute(status -> {
                PaymentIntent intent = intents.findById(snapshot.getId()).orElseThrow();
                intent.startProcessing(request.email());
                intent.useMethod("blik");
                // save() may hand back a different instance than the one given, and that one is the one tracked.
                PaymentAttempt attempt = attempts.save(PaymentAttempt.blik(Ids.generate("att"), intent.getId()));
                switch (result) {
                    case AcquirerClient.BlikResult.Pending pending -> {
                        attempt.awaitingBank(pending.acquirerRef());
                        intent.requireAction();
                        publish("PaymentRequiresAction", intent);
                    }
                    case AcquirerClient.BlikResult.Declined declined -> {
                        attempt.failed(declined.code());
                        intent.fail(declined.code(), declined.message());
                        publish("PaymentFailed", intent);
                    }
                    default -> throw new IllegalStateException("unreachable");
                }
                intents.save(intent);
                return null;
            });
        } catch (OptimisticLockingFailureException e) {
            throw ApiException.conflict("payment_not_confirmable", "This payment is already being processed", null);
        }
    }

    /** Called by the acquirer once the customer answered in their banking app. Safe to receive twice. */
    @Transactional
    public void completeBlik(String acquirerRef, boolean approved, String declineCode, String message) {
        PaymentAttempt attempt = attempts.findFirstByAcquirerRef(acquirerRef).orElse(null);
        if (attempt == null || !"blik".equals(attempt.getMethodType())) {
            log.warn("A BLIK answer names the unknown request {}", acquirerRef);
            return;
        }
        PaymentIntent intent = intents.lockById(attempt.getPaymentIntentId()).orElseThrow();
        if (intent.getStatus() != PaymentStatus.REQUIRES_ACTION) {
            return;
        }
        if (approved) {
            intent.resumeProcessing();
            approve(intent, attempt, acquirerRef);
        } else {
            String code = declineCode == null || declineCode.isBlank() ? "blik_rejected" : declineCode;
            attempt.failed(code);
            intent.fail(code, message == null || message.isBlank() ? "The payment was not confirmed in your banking app."
                    : message);
            publish("PaymentFailed", intent);
        }
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
        if (card.walletType() != null) {
            attempt.viaWallet(card.walletType());
            intent.useMethod(card.walletType());
        }
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
        // A wallet's device token belongs to that wallet; it is not a card the merchant can charge later.
        if (intent.getSetupFutureUsage() != null && intent.getCustomerId() != null && attempt.getAcquirerToken() != null
                && attempt.isCard()) {
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

    /** A buy-now-pay-later provider approved the customer for this payment. */
    public record BnplApproval(String applicationId, String provider, long amount, String currency, long fee) {
    }

    /**
     * Settles the payment with an approved BNPL application. The approval may arrive late, so the payment is checked again:
     * if it was paid another way, is held for capture, is a split, or does not match the application, the approval is
     * refused and a {@code BnplPaymentRejected} event tells the aggregator to close the application at the provider.
     * Safe to receive twice.
     */
    @Transactional
    public void applyBnplApproval(String paymentId, BnplApproval approval) {
        PaymentIntent intent = intents.lockById(paymentId).orElse(null);
        if (intent == null) {
            log.warn("A BNPL approval {} names the unknown payment {}", approval.applicationId(), paymentId);
            return;
        }
        if (approval.applicationId().equals(intent.getBnplApplicationId())) {
            return;
        }
        String refusal = bnplRefusal(intent, approval);
        if (refusal != null) {
            log.info("Refusing the BNPL approval {} for payment {}: {}", approval.applicationId(), paymentId, refusal);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("application_id", approval.applicationId());
            payload.put("payment_id", paymentId);
            payload.put("reason", refusal);
            outbox.write(KafkaTopicsConfig.PAYMENT_TOPIC, "BnplPaymentRejected", paymentId, intent.getMerchantId(),
                    intent.isLivemode(), payload);
            return;
        }
        intent.payWithBnpl(approval.provider(), approval.applicationId(), approval.fee());
        attempts.save(PaymentAttempt.bnpl(Ids.generate("att"), intent.getId(), approval.applicationId()));
        intents.save(intent);
        publish("PaymentSucceeded", intent);
    }

    private String bnplRefusal(PaymentIntent intent, BnplApproval approval) {
        if (intent.getStatus() != PaymentStatus.REQUIRES_PAYMENT_METHOD) {
            return "payment_not_payable";
        }
        if (intent.isManualCapture()) {
            return "capture_method_manual";
        }
        if (!transfers.findByPaymentIntentIdOrderByIdAsc(intent.getId()).isEmpty()) {
            return "split_payment";
        }
        if (intent.getAmount() != approval.amount() || !intent.getCurrency().equals(approval.currency())) {
            return "amount_mismatch";
        }
        if (approval.fee() < 0 || approval.fee() > intent.getAmount()) {
            return "invalid_fee";
        }
        return null;
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
        // What the customer pays. What the merchant settles in is decided later, by the ledger, when funds become available.
        payload.put("presentment_amount", intent.getAmount());
        payload.put("presentment_currency", intent.getCurrency());
        payload.put("customer_email", intent.getCustomerEmail());
        payload.put("locale", intent.getLocale());
        payload.put("failure_code", intent.getFailureCode());
        payload.put("channel", intent.getChannel());
        payload.put("off_session", intent.isOffSession());
        payload.put("metadata", intent.getMetadata());
        payload.put("payment_method_type", intent.getPaymentMethodType());
        if (intent.getBnplApplicationId() != null) {
            payload.put("bnpl_provider", intent.getBnplProvider());
            payload.put("bnpl_application_id", intent.getBnplApplicationId());
            payload.put("bnpl_fee", intent.getBnplFee());
        }
        var shares = transfers.findByPaymentIntentIdOrderByIdAsc(intent.getId());
        if (!shares.isEmpty()) {
            payload.put("transfers", shares.stream()
                    .map(t -> Map.of("destination", t.getDestination(), "amount", t.getAmount())).toList());
        }
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
