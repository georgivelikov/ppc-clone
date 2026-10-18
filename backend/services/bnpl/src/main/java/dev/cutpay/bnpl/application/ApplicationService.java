package dev.cutpay.bnpl.application;

import dev.cutpay.bnpl.adapter.Adapters;
import dev.cutpay.bnpl.adapter.BnplProviderAdapter;
import dev.cutpay.bnpl.adapter.BnplProviderAdapter.Callback;
import dev.cutpay.bnpl.application.ApplicationRepository.Application;
import dev.cutpay.bnpl.config.BnplProperties;
import dev.cutpay.bnpl.model.Applicant;
import dev.cutpay.bnpl.model.ApplicationRequest;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderCatalog;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import dev.cutpay.bnpl.model.ProviderResult;
import dev.cutpay.bnpl.model.ProviderResult.Outcome;
import dev.cutpay.bnpl.model.QuoteRequest;
import dev.cutpay.bnpl.payments.PaymentsClient;
import dev.cutpay.bnpl.payments.PaymentsClient.CheckoutFacts;
import dev.cutpay.bnpl.routing.RoutingEngine;
import dev.cutpay.bnpl.settings.SettingsService;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applications for instalments, from the customer pressing the button to the provider's answer. The provider is never
 * called while a database transaction is open: the application is stored first, the provider is asked, and the answer is
 * recorded in a second transaction. The answer can come three ways (in the reply, in a callback, or by asking again), and
 * all three end in {@link #settle}, which locks the application and ignores anything that would move it backwards, so a
 * duplicate or late message does no harm.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    /** What the customer sends to apply. */
    public record Apply(String provider, String plan, Applicant applicant, String country) {
    }

    private final Adapters adapters;
    private final ApplicationRepository applications;
    private final BnplEvents events;
    private final PaymentsClient payments;
    private final SettingsService settings;
    private final BnplProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ApplicationService(Adapters adapters, ApplicationRepository applications, BnplEvents events,
                              PaymentsClient payments, SettingsService settings, BnplProperties properties,
                              TransactionTemplate tx, Clock clock) {
        this.adapters = adapters;
        this.applications = applications;
        this.events = events;
        this.payments = payments;
        this.settings = settings;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    // --- the customer applies ----------------------------------------------------------------------------------

    public Application apply(String clientSecret, Apply request) {
        CheckoutFacts payment = payments.checkout(clientSecret);
        payment.requireBnplPayable();
        BnplProviderAdapter adapter = adapters.require(request.provider());
        String country = request.country() == null || request.country().isBlank() ? properties.defaultCountry()
                : request.country().trim().toUpperCase(Locale.ROOT);

        var eligibility = RoutingEngine.eligible(payment.amount(), payment.currency(), country,
                settings.get(payment.merchantId(), payment.livemode()), Set.of());
        if (eligibility.eligible().stream().noneMatch(p -> p.code().equals(request.provider()))) {
            throw ApiException.conflict("provider_not_available", "This provider cannot finance this payment", "provider");
        }
        Applicant applicant = request.applicant() == null ? new Applicant(null, null, null, null, null) : request.applicant();
        List<String> missing = applicant.missing(adapter.requiredFields());
        if (!missing.isEmpty()) {
            throw ApiException.invalidRequest("applicant_incomplete", "Missing: " + String.join(", ", missing), missing.get(0));
        }

        // The offer is priced again now, so the customer agrees to what the provider offers at this moment.
        Offer offer = quoteAgain(adapter, new QuoteRequest(payment.amount(), payment.currency(), country), request.plan());
        Application stored = tx.execute(status -> {
            applications.blockingFor(payment.paymentId()).ifPresent(existing -> {
                throw ApiException.conflict("application_in_progress",
                        "There is already an application for this payment", "payment");
            });
            Instant now = clock.instant();
            Application created = new Application(Ids.generate("bnpl"), payment.paymentId(), payment.merchantId(),
                    payment.livemode(), offer.provider(), offer.plan(), ApplicationStatus.CREATED, null, payment.amount(),
                    payment.currency(), country, offer.instalmentCount(), offer.instalments(), offer.intervalDays(),
                    offer.aprPercent(), offer.totalPayable(), offer.merchantFee(), applicant.email(), null, null,
                    now.plus(properties.applicationExpiry()), now, null, now);
            applications.insert(created);
            events.created(created);
            return created;
        });

        long started = System.nanoTime();
        ProviderResult result;
        try {
            result = ask(adapter.info().code(), () -> adapter.createApplication(new ApplicationRequest(stored.id(),
                    payment.amount(), payment.currency(), offer.plan(), offer.instalmentCount(), applicant)));
        } catch (ProviderException e) {
            log.warn("Application {} could not be sent: {}", stored.id(), e.getMessage());
            fail(stored.id(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "api_error", "provider_unavailable",
                    ProviderCatalog.find(stored.provider()).orElseThrow().name()
                            + " could not take the application. You can try another option.", null);
        }
        return settle(stored.id(), result, (System.nanoTime() - started) / 1_000_000);
    }

    private Offer quoteAgain(BnplProviderAdapter adapter, QuoteRequest quote, String plan) {
        List<Offer> offers;
        try {
            offers = ask(adapter.info().code(), () -> adapter.quote(quote));
        } catch (ProviderException e) {
            log.warn("Could not price {} again: {}", adapter.info().code(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "api_error", "provider_unavailable",
                    adapter.info().name() + " is not available right now. You can try another option.", null);
        }
        return offers.stream().filter(o -> o.plan().equals(plan)).findFirst().orElseThrow(() -> ApiException.conflict(
                "offer_not_available", "This offer is no longer available. Reload the offers and choose again.", "plan"));
    }

    // --- reading -----------------------------------------------------------------------------------------------

    /** The customer looking at their own application: it must belong to the payment of the secret they hold. */
    public Application forCheckout(String clientSecret, String id) {
        CheckoutFacts payment = payments.checkout(clientSecret);
        return applications.find(id).filter(a -> a.paymentId().equals(payment.paymentId()))
                .orElseThrow(() -> ApiException.notFound("application_not_found", "No such application"));
    }

    public Application get(String merchantId, boolean livemode, String id) {
        return applications.find(id).filter(a -> a.merchantId().equals(merchantId) && a.livemode() == livemode)
                .orElseThrow(() -> ApiException.notFound("application_not_found", "No such application"));
    }

    public List<Application> list(String merchantId, boolean livemode, String status, String provider, String payment,
                                  String startingAfter, int limit) {
        return applications.page(merchantId, livemode, blank(status), blank(provider), blank(payment), blank(startingAfter),
                Math.max(1, Math.min(limit, 100)) + 1);
    }

    // --- the provider answers ----------------------------------------------------------------------------------

    /** A provider calls back. The adapter checks that it really is the provider before anything is believed. */
    public void onCallback(String providerCode, Map<String, String> headers, String body) {
        BnplProviderAdapter adapter = adapters.find(providerCode)
                .orElseThrow(() -> ApiException.notFound("unknown_provider", "No such provider"));
        Optional<Callback> callback;
        try {
            callback = adapter.handleCallback(headers, body);
        } catch (SecurityException e) {
            log.warn("Rejected a {} callback: {}", providerCode, e.getMessage());
            throw ApiException.unauthorized("invalid_signature", "The callback could not be verified");
        } catch (ProviderException e) {
            throw ApiException.invalidRequest("invalid_callback", "The callback could not be read", null);
        }
        Callback received = callback.orElseThrow(() -> ApiException.notFound("no_callbacks", "This provider does not call back"));
        Application application = applications.find(received.reference())
                .filter(a -> a.provider().equals(providerCode))
                .orElseThrow(() -> ApiException.notFound("application_not_found", "No such application"));
        settle(application.id(), received.result(), null);
    }

    /** Asks the provider about applications that wait for a decision; for those that never call back, and for lost callbacks. */
    public int pollWaiting(int limit) {
        int answered = 0;
        for (Application waiting : applications.waiting(limit)) {
            if (waiting.providerRef() == null) {
                continue;
            }
            try {
                ProviderResult result = ask(waiting.provider(), () -> adapters.require(waiting.provider()).status(waiting.providerRef()));
                if (result.outcome() != Outcome.PENDING) {
                    settle(waiting.id(), result, null);
                    answered++;
                }
            } catch (RuntimeException e) {
                log.debug("Could not ask {} about {}: {}", waiting.provider(), waiting.id(), e.getMessage());
            }
        }
        return answered;
    }

    /** Closes applications nobody decided in time, and tells the provider to forget them. */
    public int expireStale(int limit) {
        int expired = 0;
        for (Application stale : applications.expired(clock.instant(), limit)) {
            Boolean moved = tx.execute(status -> {
                Application locked = applications.lock(stale.id()).orElseThrow();
                if (!locked.status().canMoveTo(ApplicationStatus.EXPIRED)) {
                    return false;
                }
                applications.update(locked.id(), ApplicationStatus.EXPIRED, null, "expired", null, null, clock.instant());
                events.expired(applications.find(locked.id()).orElseThrow());
                return true;
            });
            if (Boolean.TRUE.equals(moved)) {
                expired++;
                closeAtProvider(stale);
            }
        }
        return expired;
    }

    // --- closing -----------------------------------------------------------------------------------------------

    /** The customer gives up on an application that has not been decided. */
    public Application cancelByCustomer(String clientSecret, String id) {
        Application application = forCheckout(clientSecret, id);
        if (!application.status().isOpen()) {
            throw ApiException.conflict("application_not_cancelable", "This application has already been decided", null);
        }
        return cancel(application.id(), "canceled_by_customer");
    }

    /** The payment was paid another way while this application was alive, so the provider must not collect on it. */
    public void cancelBecausePaidElsewhere(String id) {
        cancel(id, "payment_settled_elsewhere");
    }

    private Application cancel(String id, String reason) {
        Application[] closed = new Application[1];
        tx.executeWithoutResult(status -> {
            Application locked = applications.lock(id).orElseThrow(() -> ApiException.notFound("application_not_found", "No such application"));
            if (!locked.status().canMoveTo(ApplicationStatus.CANCELED)) {
                throw ApiException.conflict("application_not_cancelable", "This application can no longer be canceled", null);
            }
            applications.update(id, ApplicationStatus.CANCELED, null, reason, null, null, clock.instant());
            closed[0] = applications.find(id).orElseThrow();
            events.canceled(closed[0]);
        });
        closeAtProvider(closed[0]);
        return closed[0];
    }

    private void closeAtProvider(Application application) {
        if (application.providerRef() == null) {
            return;
        }
        try {
            adapters.require(application.provider()).cancel(application.providerRef());
        } catch (RuntimeException e) {
            // The application is closed on our side whatever the provider says; it will refuse to settle a closed one.
            log.warn("Could not tell {} that {} is closed: {}", application.provider(), application.id(), e.getMessage());
        }
    }

    // --- recording answers -------------------------------------------------------------------------------------

    /** Records what the provider said. Idempotent: an answer that does not fit where the application is now is ignored. */
    Application settle(String id, ProviderResult result, Long measuredMs) {
        return tx.execute(status -> {
            Application locked = applications.lock(id).orElseThrow();
            ApplicationStatus target = switch (result.outcome()) {
                case APPROVED -> ApplicationStatus.APPROVED;
                case DECLINED -> ApplicationStatus.DECLINED;
                case PENDING -> ApplicationStatus.PENDING_REVIEW;
                case CANCELED -> ApplicationStatus.CANCELED;
            };
            if (locked.status() == target && locked.providerRef() != null) {
                return locked;
            }
            if (!locked.status().canMoveTo(target)) {
                log.info("Ignoring {} for application {} which is {}", result.outcome(), id, locked.status().wire());
                return locked;
            }
            Instant now = clock.instant();
            boolean decided = result.outcome() != Outcome.PENDING;
            Long ms = decided ? (measuredMs != null ? measuredMs : Duration.between(locked.createdAt(), now).toMillis()) : null;
            applications.update(id, target, result.providerRef(), result.declineReason(), ms, decided ? now : null, now);
            Application updated = applications.find(id).orElseThrow();
            switch (target) {
                case APPROVED -> events.approved(updated);
                case DECLINED -> events.declined(updated);
                case CANCELED -> events.canceled(updated);
                default -> { }
            }
            return updated;
        });
    }

    private void fail(String id, ProviderException cause) {
        tx.executeWithoutResult(status -> {
            Application locked = applications.lock(id).orElseThrow();
            if (!locked.status().canMoveTo(ApplicationStatus.FAILED) && !locked.status().canMoveTo(ApplicationStatus.DECLINED)) {
                return;
            }
            Instant now = clock.instant();
            if (cause.kind() == Kind.REFUSED) {
                // The provider looked at the application and said no.
                applications.update(id, ApplicationStatus.DECLINED, null, "refused_by_provider", null, now, now);
                events.declined(applications.find(id).orElseThrow());
                return;
            }
            applications.update(id, ApplicationStatus.FAILED, null, cause.kind() == Kind.TIMEOUT ? "provider_timeout" : "provider_unavailable", null, null, now);
            Application failed = applications.find(id).orElseThrow();
            if (cause.kind() == Kind.TIMEOUT) {
                events.timedOut(failed);
            } else {
                events.providerFailed(failed);
            }
        });
    }

    /** An answer that cannot be read (a missing field, the wrong shape) is a provider problem, not a crash of ours. */
    private static <T> T ask(String provider, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (ProviderException | ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProviderException(provider, Kind.INVALID_RESPONSE, "unreadable answer", e);
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
