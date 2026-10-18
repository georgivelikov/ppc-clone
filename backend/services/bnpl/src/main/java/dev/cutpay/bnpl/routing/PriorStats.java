package dev.cutpay.bnpl.routing;

import dev.cutpay.bnpl.model.ProviderCatalog;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What a provider is assumed to do before there is any history: roughly what its published behaviour suggests.
 * Measured figures replace these as applications come in.
 */
@Component
public class PriorStats implements StatsSource {

    private static final Map<String, Stats> PRIORS = Map.of(
            ProviderCatalog.SPLITNOW, new Stats(0.85, 300),
            ProviderCatalog.LENDO, new Stats(0.70, 12_000),
            ProviderCatalog.KREDITKO, new Stats(0.55, 45_000),
            ProviderCatalog.PAYLATEREU, new Stats(0.80, 600));

    @Override
    public Stats stats(String provider) {
        return PRIORS.getOrDefault(provider, new Stats(0.5, 30_000));
    }
}
