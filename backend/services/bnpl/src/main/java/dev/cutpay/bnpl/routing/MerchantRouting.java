package dev.cutpay.bnpl.routing;

import dev.cutpay.bnpl.model.ProviderCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A merchant's routing choices: how to pick, how much each thing counts in the weighted mode, and for every provider
 * whether it is on and where it stands in the merchant's order (a lower priority comes first).
 */
public record MerchantRouting(RoutingMode mode, Weights weights, List<ProviderSetting> providers) {

    public record Weights(int cost, int speed, int approval) {
        public static final Weights EQUAL = new Weights(1, 1, 1);

        public int total() {
            return cost + speed + approval;
        }
    }

    public record ProviderSetting(String code, boolean enabled, int priority) {
    }

    /** Every provider on, in the catalogue order, customer choice: what a merchant gets before configuring anything. */
    public static MerchantRouting defaults() {
        return withSettings(RoutingMode.CUSTOMER_CHOICE, Weights.EQUAL, List.of());
    }

    /** The given settings with every provider of the catalogue present: those not mentioned are on, after the others. */
    public static MerchantRouting withSettings(RoutingMode mode, Weights weights, List<ProviderSetting> given) {
        Map<String, ProviderSetting> byCode = given.stream().collect(Collectors.toMap(ProviderSetting::code, Function.identity(), (a, b) -> b));
        List<ProviderSetting> all = new ArrayList<>();
        int next = given.stream().mapToInt(ProviderSetting::priority).max().orElse(-1) + 1;
        for (var info : ProviderCatalog.ALL) {
            ProviderSetting setting = byCode.get(info.code());
            all.add(setting != null ? setting : new ProviderSetting(info.code(), true, byCode.isEmpty() ? ProviderCatalog.position(info.code()) : next++));
        }
        all.sort(Comparator.comparingInt(ProviderSetting::priority).thenComparingInt(s -> ProviderCatalog.position(s.code())));
        return new MerchantRouting(mode, weights, List.copyOf(all));
    }

    public ProviderSetting setting(String code) {
        return providers.stream().filter(s -> s.code().equals(code)).findFirst()
                .orElse(new ProviderSetting(code, false, Integer.MAX_VALUE));
    }
}
