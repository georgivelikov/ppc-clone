package dev.cutpay.bnpl.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The providers Cutpay can route to, in the order they are preferred when a merchant has not chosen. */
public final class ProviderCatalog {

    public static final String SPLITNOW = "splitnow";
    public static final String LENDO = "lendo";
    public static final String KREDITKO = "kreditko";
    public static final String PAYLATEREU = "paylatereu";

    public static final List<ProviderInfo> ALL = List.of(
            new ProviderInfo(SPLITNOW, "SplitNow", 5_000, 150_000, Set.of("EUR"),
                    Set.of("BG", "DE", "NL", "AT", "BE", "FR", "ES", "IT", "IE", "PT")),
            new ProviderInfo(LENDO, "Lendo", 10_000, 1_000_000, Set.of("EUR"), Set.of("BG", "DE", "AT", "RO", "PL", "GR")),
            new ProviderInfo(KREDITKO, "KreditKo", 20_000, 1_500_000, Set.of("EUR"), Set.of("BG", "RO", "GR", "HR")),
            new ProviderInfo(PAYLATEREU, "PayLaterEU", 2_000, 80_000, Set.of("EUR"),
                    Set.of("DE", "NL", "AT", "BE", "FR", "IE", "BG")));

    private ProviderCatalog() {
    }

    public static Optional<ProviderInfo> find(String code) {
        return ALL.stream().filter(p -> p.code().equals(code)).findFirst();
    }

    /** Where a provider stands in the default order; unknown codes go last. */
    public static int position(String code) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).code().equals(code)) {
                return i;
            }
        }
        return ALL.size();
    }
}
