package dev.cutpay.bnpl.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cutpay.bnpl.model.ProviderException;
import dev.cutpay.bnpl.model.ProviderException.Kind;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProviderHealthTest {

    private static ProviderHealth health() {
        return new ProviderHealth(4, 3, 50, Duration.ofSeconds(30), 1);
    }

    private static void fail(CircuitBreaker breaker, Throwable cause) {
        assertThatThrownBy(() -> breaker.executeRunnable(() -> {
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        })).isNotNull();
    }

    @Test
    void whatCountsAgainstAProviderAndWhatDoesNot() {
        assertThat(ProviderHealth.countsAgainstTheProvider(new ProviderException("p", Kind.TIMEOUT, "slow"))).isTrue();
        assertThat(ProviderHealth.countsAgainstTheProvider(new ProviderException("p", Kind.UNAVAILABLE, "down"))).isTrue();
        assertThat(ProviderHealth.countsAgainstTheProvider(new ProviderException("p", Kind.INVALID_RESPONSE, "garbage"))).isTrue();
        assertThat(ProviderHealth.countsAgainstTheProvider(new NullPointerException())).isTrue();
        assertThat(ProviderHealth.countsAgainstTheProvider(new ProviderException("p", Kind.REFUSED, "no"))).isFalse();
        assertThat(ProviderHealth.countsAgainstTheProvider(new SecurityException("bad signature"))).isFalse();
    }

    @Test
    void theCircuitOpensAfterEnoughFailuresAndIsReportedUnavailable() {
        ProviderHealth health = health();
        CircuitBreaker breaker = health.breaker("lendo");
        for (int i = 0; i < 2; i++) {
            fail(breaker, new ProviderException("lendo", Kind.TIMEOUT, "slow"));
        }
        assertThat(health.unavailable()).isEmpty();

        fail(breaker, new ProviderException("lendo", Kind.TIMEOUT, "slow"));

        assertThat(health.unavailable()).containsExactly("lendo");
        assertThat(health.state("lendo")).isEqualTo("open");
        assertThat(health.state("splitnow")).isEqualTo("closed");

        health.resetAll();
        assertThat(health.unavailable()).isEmpty();
    }

    @Test
    void refusalsNeverOpenTheCircuit() {
        ProviderHealth health = health();
        CircuitBreaker breaker = health.breaker("kreditko");
        for (int i = 0; i < 20; i++) {
            fail(breaker, new ProviderException("kreditko", Kind.REFUSED, "no"));
        }

        assertThat(health.unavailable()).isEmpty();
    }
}
