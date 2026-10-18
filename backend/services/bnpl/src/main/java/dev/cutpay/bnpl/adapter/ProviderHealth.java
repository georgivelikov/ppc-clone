package dev.cutpay.bnpl.adapter;

import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One circuit breaker per provider. When too many recent calls to a provider time out or fail, its circuit opens: for a
 * while it is not called at all and is left out of the offers, so that a provider that is down costs the customer no
 * waiting. After the waiting time it lets a few probe calls through (half open); if they succeed the circuit closes and the
 * provider is back, if they fail it opens again.
 *
 * <p>A provider that answers and says no, for example an amount outside its limits, is working, so a refusal never counts
 * against it.
 */
@Component
public class ProviderHealth {

    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final CircuitBreakerConfig config;

    ProviderHealth(@Value("${cutpay.bnpl.breaker.window:10}") int window,
                   @Value("${cutpay.bnpl.breaker.minimum-calls:5}") int minimumCalls,
                   @Value("${cutpay.bnpl.breaker.failure-rate:50}") int failureRate,
                   @Value("${cutpay.bnpl.breaker.wait-in-open:PT10S}") Duration waitInOpen,
                   @Value("${cutpay.bnpl.breaker.probe-calls:2}") int probeCalls) {
        this.config = CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(window)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRate)
                .waitDurationInOpenState(waitInOpen)
                // Open becomes half open by itself once the time has passed, so the next offers request is the probe.
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(probeCalls)
                .recordException(ProviderHealth::countsAgainstTheProvider)
                .build();
    }

    /** Whatever goes wrong with the provider counts, except it saying no or us rejecting a callback. */
    static boolean countsAgainstTheProvider(Throwable e) {
        if (e instanceof ProviderException provider) {
            return provider.kind() != Kind.REFUSED;
        }
        return !(e instanceof SecurityException);
    }

    public CircuitBreaker breaker(String provider) {
        return breakers.computeIfAbsent(provider, code -> CircuitBreaker.of(code, config));
    }

    /** The providers whose circuit is open: they are not called and not offered. Half open ones may be probed. */
    public Set<String> unavailable() {
        Set<String> open = new LinkedHashSet<>();
        breakers.forEach((code, breaker) -> {
            if (breaker.getState() == CircuitBreaker.State.OPEN || breaker.getState() == CircuitBreaker.State.FORCED_OPEN) {
                open.add(code);
            }
        });
        return open;
    }

    public String state(String provider) {
        return breaker(provider).getState().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Forgets everything that was seen; every circuit closes. For tests and for an operator who knows better. */
    public void resetAll() {
        breakers.values().forEach(CircuitBreaker::reset);
    }
}
