package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.model.ApplicationRequest;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.model.ProviderResult;
import dev.cutpay.bnpl.model.QuoteRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The anti-corruption layer for one provider: Cutpay's words go in and out, and the provider's units, field names and
 * ways of answering stay inside the implementation. Every method throws {@link ProviderException} when the provider
 * cannot be asked or answers in a way that makes no sense.
 */
public interface BnplProviderAdapter {

    /** A callback from a provider, checked and translated. {@code reference} is the id Cutpay gave the application. */
    record Callback(String reference, ProviderResult result) {
    }

    ProviderInfo info();

    /** The things the customer must tell this provider, as {@code Applicant} field names. */
    List<String> requiredFields();

    /** The ways the provider offers to pay {@code request.amount()}. */
    List<Offer> quote(QuoteRequest request);

    /** Sends the customer's application. The answer may already be a decision or may be {@code PENDING}. */
    ProviderResult createApplication(ApplicationRequest request);

    /** Asks the provider where an application stands; for those that decide later and for providers that never call back. */
    ProviderResult status(String providerRef);

    /** Closes an application that has not been used, or whose payment was settled another way. */
    void cancel(String providerRef);

    /**
     * Checks and reads a callback. Empty if this provider does not call back. Throws {@link SecurityException} when the
     * callback cannot be trusted, for example a signature that does not match.
     */
    default Optional<Callback> handleCallback(Map<String, String> headers, String body) {
        return Optional.empty();
    }
}
