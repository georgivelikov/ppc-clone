package dev.cutpay.payments.customer;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.payments.acquirer.AcquirerClient;
import dev.cutpay.payments.acquirer.AcquirerClient.AuthResult;
import dev.cutpay.payments.acquirer.AcquirerClient.CardInfo;
import dev.cutpay.payments.config.KafkaTopicsConfig;
import dev.cutpay.payments.customer.CustomerDtos.CreateSetupIntentRequest;
import dev.cutpay.payments.customer.CustomerDtos.SetupIntentView;
import dev.cutpay.payments.customer.CustomerDtos.SetupSessionView;
import dev.cutpay.payments.domain.PaymentMethod;
import dev.cutpay.payments.domain.Repositories.SetupIntentRepository;
import dev.cutpay.payments.domain.SetupIntent;
import dev.cutpay.payments.payment.PaymentDtos.NextAction;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saves a customer card without charging it. The customer enters the card on a hosted page, the bank may ask for
 * 3-D Secure once, and afterwards the card can be charged off-session. As with a payment, the call to the card
 * network is made outside any database transaction.
 */
@Service
public class SetupIntentService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PROCESSING_ERROR_MESSAGE = "We could not save your card. Please try again.";

    private final SetupIntentRepository setups;
    private final CustomerService customers;
    private final AcquirerClient acquirer;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String checkoutUrl;

    public SetupIntentService(SetupIntentRepository setups, CustomerService customers, AcquirerClient acquirer,
                              OutboxWriter outbox, TransactionTemplate tx, Clock clock,
                              @Value("${cutpay.checkout-url}") String checkoutUrl) {
        this.setups = setups;
        this.customers = customers;
        this.acquirer = acquirer;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.checkoutUrl = checkoutUrl;
    }

    public SetupIntentView create(String merchantId, boolean livemode, CreateSetupIntentRequest request) {
        return tx.execute(status -> {
            var customer = customers.find(merchantId, livemode, request.customer());
            String id = Ids.generate("seti");
            var setup = new SetupIntent(id, id + "_secret_" + randomSecret(), merchantId, request.merchantName(),
                    customer.isLivemode(), customer.getId(), request.description(), request.returnUrl(),
                    clock.instant());
            setups.save(setup);
            return SetupIntentView.of(setup, checkoutUrl);
        });
    }

    public SetupIntentView get(String merchantId, Boolean claimedMode, String id) {
        return SetupIntentView.of(find(merchantId, claimedMode, id), checkoutUrl);
    }

    public SetupIntentView cancel(String merchantId, Boolean claimedMode, String id) {
        find(merchantId, claimedMode, id);
        return tx.execute(status -> {
            SetupIntent setup = setups.lockById(id).orElseThrow();
            setup.cancel(clock.instant());
            return SetupIntentView.of(setup, checkoutUrl);
        });
    }

    // --- hosted page -----------------------------------------------------------------------------

    public SetupSessionView session(String clientSecret) {
        SetupIntent setup = setups.findByClientSecret(clientSecret)
                .orElseThrow(() -> ApiException.notFound("session_not_found", "This link is not valid"));
        NextAction next = setup.getStatus() == SetupIntent.Status.REQUIRES_ACTION
                ? new NextAction("three_ds", setup.getAcquirerRef()) : null;
        return new SetupSessionView(setup.getId(), setup.getStatus().wire(), setup.getMerchantName(),
                setup.getDescription(), setup.getReturnUrl(), setup.getFailureCode(), setup.getFailureMessage(), next);
    }

    public SetupSessionView confirm(String clientSecret, String token) {
        SetupIntent snapshot = setups.findByClientSecret(clientSecret)
                .orElseThrow(() -> ApiException.notFound("session_not_found", "This link is not valid"));
        if (snapshot.getStatus() != SetupIntent.Status.REQUIRES_PAYMENT_METHOD) {
            throw ApiException.conflict("setup_not_confirmable", "This card can no longer be saved here", null);
        }
        CardInfo card = acquirer.describeToken(token);
        AuthResult result = acquirer.verify(token);
        try {
            tx.execute(status -> {
                settle(snapshot.getId(), token, card, result);
                return null;
            });
        } catch (OptimisticLockingFailureException e) {
            throw ApiException.conflict("setup_not_confirmable", "This card is already being saved", null);
        }
        return session(clientSecret);
    }

    private void settle(String id, String token, CardInfo card, AuthResult result) {
        SetupIntent setup = setups.lockById(id).orElseThrow();
        if (setup.getStatus() != SetupIntent.Status.REQUIRES_PAYMENT_METHOD) {
            throw ApiException.conflict("setup_not_confirmable", "This card is already being saved", null);
        }
        switch (result) {
            case AuthResult.Approved approved -> succeed(setup, card, token);
            case AuthResult.RequiresAction action -> setup.requireAction(token, action.acquirerRef(), clock.instant());
            case AuthResult.Declined declined -> fail(setup, declined.code(), declined.message());
            case AuthResult.Unavailable unavailable -> fail(setup, "processing_error", PROCESSING_ERROR_MESSAGE);
        }
    }

    /** The customer answered the bank challenge. Returns false when the reference is not a card being saved. */
    public boolean completeThreeDs(String acquirerRef, boolean approved, String declineCode, String message) {
        var found = setups.findFirstByAcquirerRef(acquirerRef);
        if (found.isEmpty()) {
            return false;
        }
        SetupIntent snapshot = found.get();
        CardInfo card = null;
        if (approved) {
            try {
                card = acquirer.describeToken(snapshot.getAcquirerToken());
            } catch (ApiException e) {
                approved = false;
                declineCode = "processing_error";
                message = PROCESSING_ERROR_MESSAGE;
            }
        }
        boolean ok = approved;
        CardInfo verified = card;
        String code = declineCode == null || declineCode.isBlank() ? "authentication_failed" : declineCode;
        String text = message == null || message.isBlank() ? "3-D Secure authentication failed." : message;
        tx.execute(status -> {
            SetupIntent setup = setups.lockById(snapshot.getId()).orElseThrow();
            if (setup.getStatus() != SetupIntent.Status.REQUIRES_ACTION) {
                return null;
            }
            if (ok) {
                succeed(setup, verified, setup.getAcquirerToken());
            } else {
                fail(setup, code, text);
            }
            return null;
        });
        return true;
    }

    // --- internals -------------------------------------------------------------------------------

    private void succeed(SetupIntent setup, CardInfo card, String token) {
        PaymentMethod method = customers.saveCard(setup.getMerchantId(), setup.getCustomerId(), card, token);
        setup.succeed(method.getId(), clock.instant());
        publish("SetupIntentSucceeded", setup);
    }

    private void fail(SetupIntent setup, String code, String message) {
        setup.fail(code, message, clock.instant());
        publish("SetupIntentFailed", setup);
    }

    private SetupIntent find(String merchantId, Boolean claimedMode, String id) {
        SetupIntent setup = setups.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> ApiException.notFound("setup_intent_not_found", "No such setup intent"));
        if (claimedMode != null && claimedMode != setup.isLivemode()) {
            throw ApiException.notFound("setup_intent_not_found", "No such setup intent");
        }
        return setup;
    }

    private void publish(String eventType, SetupIntent setup) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("setup_intent_id", setup.getId());
        payload.put("customer_id", setup.getCustomerId());
        payload.put("payment_method_id", setup.getPaymentMethodId());
        payload.put("status", setup.getStatus().wire());
        payload.put("failure_code", setup.getFailureCode());
        outbox.write(KafkaTopicsConfig.CUSTOMER_TOPIC, eventType, setup.getId(), setup.getMerchantId(),
                setup.isLivemode(), payload);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
