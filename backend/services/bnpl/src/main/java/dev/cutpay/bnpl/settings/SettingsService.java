package dev.cutpay.bnpl.settings;

import dev.cutpay.bnpl.model.ProviderCatalog;
import dev.cutpay.bnpl.routing.MerchantRouting;
import dev.cutpay.bnpl.routing.MerchantRouting.ProviderSetting;
import dev.cutpay.bnpl.routing.MerchantRouting.Weights;
import dev.cutpay.bnpl.routing.RoutingMode;
import dev.cutpay.web.ApiException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SettingsService {

    /** A provider to switch on or off, or to move; what is left out stays as it is. */
    public record ProviderChange(String code, Boolean enabled, Integer priority) {
    }

    /** What a merchant may change; whatever is left out stays as it is. */
    public record Changes(String routingMode, Weights weights, List<ProviderChange> providers) {
    }

    private final SettingsRepository settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SettingsService(SettingsRepository settings, TransactionTemplate tx, Clock clock) {
        this.settings = settings;
        this.tx = tx;
        this.clock = clock;
    }

    public MerchantRouting get(String merchantId, boolean livemode) {
        return settings.load(merchantId, livemode);
    }

    /**
     * Switches providers on or off and moves them. A priority is a position, 0 being first: the providers given a priority
     * take exactly those positions and the others fill the remaining ones in the order they had.
     */
    private static List<ProviderSetting> apply(MerchantRouting current, List<ProviderChange> changes) {
        int size = current.providers().size();
        Set<String> seen = new HashSet<>();
        Map<String, Boolean> enabled = new HashMap<>();
        Map<Integer, String> placed = new HashMap<>();
        for (ProviderChange change : changes) {
            if (ProviderCatalog.find(change.code()).isEmpty()) {
                throw ApiException.invalidRequest("unknown_provider", "No such provider: " + change.code(), "providers");
            }
            if (!seen.add(change.code())) {
                throw ApiException.invalidRequest("duplicate_provider", "Each provider can appear once", "providers");
            }
            if (change.enabled() != null) {
                enabled.put(change.code(), change.enabled());
            }
            if (change.priority() != null) {
                int position = Math.min(Math.max(change.priority(), 0), size - 1);
                if (placed.putIfAbsent(position, change.code()) != null) {
                    throw ApiException.invalidRequest("duplicate_priority", "Two providers cannot take the same position", "providers");
                }
            }
        }
        Set<String> placedCodes = new HashSet<>(placed.values());
        Iterator<String> others = current.providers().stream().map(ProviderSetting::code)
                .filter(code -> !placedCodes.contains(code)).iterator();
        List<ProviderSetting> result = new ArrayList<>();
        for (int position = 0; position < size; position++) {
            String code = placed.containsKey(position) ? placed.get(position) : others.next();
            result.add(new ProviderSetting(code, enabled.getOrDefault(code, current.setting(code).enabled()), position));
        }
        return result;
    }

    public MerchantRouting update(String merchantId, boolean livemode, Changes changes) {
        MerchantRouting current = settings.load(merchantId, livemode);
        RoutingMode mode = current.mode();
        if (changes.routingMode() != null) {
            mode = RoutingMode.fromWire(changes.routingMode()).orElseThrow(() -> ApiException.invalidRequest(
                    "invalid_routing_mode", "Routing mode must be customer_choice, cheapest, fastest, best_approval or weighted",
                    "routing_mode"));
        }
        Weights weights = current.weights();
        if (changes.weights() != null) {
            weights = changes.weights();
            if (weights.cost() < 0 || weights.speed() < 0 || weights.approval() < 0 || weights.total() == 0) {
                throw ApiException.invalidRequest("invalid_weights",
                        "Weights are whole numbers from 0 up, and at least one must be above 0", "weights");
            }
        }
        List<ProviderSetting> providers = current.providers();
        if (changes.providers() != null) {
            providers = apply(current, changes.providers());
        }
        MerchantRouting next = MerchantRouting.withSettings(mode, weights, providers);
        tx.executeWithoutResult(status -> settings.save(merchantId, livemode, next, clock.instant()));
        return next;
    }
}
