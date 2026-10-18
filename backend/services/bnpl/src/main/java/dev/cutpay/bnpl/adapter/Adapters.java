package dev.cutpay.bnpl.adapter;

import dev.cutpay.web.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The adapters by provider code. */
@Component
public class Adapters {

    private final Map<String, BnplProviderAdapter> byCode = new HashMap<>();

    public Adapters(List<BnplProviderAdapter> adapters) {
        adapters.forEach(adapter -> byCode.put(adapter.info().code(), adapter));
    }

    public Optional<BnplProviderAdapter> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public BnplProviderAdapter require(String code) {
        return find(code).orElseThrow(() -> ApiException.invalidRequest("unknown_provider", "No such provider: " + code, "provider"));
    }
}
