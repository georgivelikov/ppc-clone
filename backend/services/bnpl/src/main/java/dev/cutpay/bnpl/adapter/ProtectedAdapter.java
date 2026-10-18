package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.model.ApplicationRequest;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.model.ProviderResult;
import dev.cutpay.bnpl.model.QuoteRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * An adapter behind its circuit breaker. Every call to the provider goes through the same breaker, so an outage seen while
 * quoting also protects the applications that follow. When the circuit is open the call is refused at once, as an
 * "unavailable" failure like any other, without the provider being bothered.
 */
final class ProtectedAdapter implements BnplProviderAdapter {

    private final BnplProviderAdapter delegate;
    private final CircuitBreaker breaker;

    ProtectedAdapter(BnplProviderAdapter delegate, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker = breaker;
    }

    private <T> T guarded(Supplier<T> call) {
        try {
            return breaker.executeSupplier(call);
        } catch (CallNotPermittedException e) {
            throw new ProviderException(delegate.info().code(), Kind.UNAVAILABLE, "circuit open", e);
        }
    }

    @Override
    public ProviderInfo info() {
        return delegate.info();
    }

    @Override
    public List<String> requiredFields() {
        return delegate.requiredFields();
    }

    @Override
    public List<Offer> quote(QuoteRequest request) {
        return guarded(() -> delegate.quote(request));
    }

    @Override
    public ProviderResult createApplication(ApplicationRequest request) {
        return guarded(() -> delegate.createApplication(request));
    }

    @Override
    public ProviderResult status(String providerRef) {
        return guarded(() -> delegate.status(providerRef));
    }

    @Override
    public void cancel(String providerRef) {
        guarded(() -> {
            delegate.cancel(providerRef);
            return Boolean.TRUE;
        });
    }

    @Override
    public void refund(String providerRef, long amount) {
        guarded(() -> {
            delegate.refund(providerRef, amount);
            return Boolean.TRUE;
        });
    }

    /** A callback comes from the provider, so it is not a call to it and does not go through the breaker. */
    @Override
    public Optional<Callback> handleCallback(Map<String, String> headers, String body) {
        return delegate.handleCallback(headers, body);
    }
}
