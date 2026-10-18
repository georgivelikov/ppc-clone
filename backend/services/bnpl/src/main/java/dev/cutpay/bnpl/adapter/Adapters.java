package dev.cutpay.bnpl.adapter;

import dev.cutpay.web.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The adapters by provider code, each behind its circuit breaker. */
@Component
public class Adapters {

    private final Map<String, BnplProviderAdapter> byCode = new HashMap<>();
    private final ProviderHealth health;

    public Adapters(List<BnplProviderAdapter> adapters, ProviderHealth health) {
        this.health = health;
        for (BnplProviderAdapter adapter : adapters) {
            String code = adapter.info().code();
            byCode.put(code, new ProtectedAdapter(adapter, health.breaker(code)));
        }
    }

    public Optional<BnplProviderAdapter> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public BnplProviderAdapter require(String code) {
        return find(code).orElseThrow(() -> ApiException.invalidRequest("unknown_provider", "No such provider: " + code, "provider"));
    }

    /** The providers that should not be called or offered right now because their circuit is open. */
    public Set<String> unavailable() {
        return health.unavailable();
    }

    public String circuit(String code) {
        return health.state(code);
    }
}
