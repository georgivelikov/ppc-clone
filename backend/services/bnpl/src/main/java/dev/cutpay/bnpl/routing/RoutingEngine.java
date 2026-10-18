package dev.cutpay.bnpl.routing;

import dev.cutpay.bnpl.model.Money;
import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.model.ProviderCatalog;
import dev.cutpay.bnpl.model.ProviderInfo;
import dev.cutpay.bnpl.routing.Decision.Ranked;
import dev.cutpay.bnpl.routing.Eligibility.Exclusion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks a provider in two steps that touch nothing outside this class. First {@link #eligible} says which providers
 * may be asked at all, so the others are never called. Then {@link #decide} ranks the ones that answered, by the
 * merchant's mode, and writes down why, so the dashboard can explain the choice.
 */
public final class RoutingEngine {

    private RoutingEngine() {
    }

    /**
     * Filters the catalogue: the merchant has it on, it works in this currency and country, the amount is within its
     * limits and it is not known to be down.
     */
    public static Eligibility eligible(long amount, String currency, String country, MerchantRouting routing,
                                       Set<String> unavailable) {
        List<ProviderInfo> eligible = new ArrayList<>();
        List<Exclusion> excluded = new ArrayList<>();
        for (ProviderInfo provider : ProviderCatalog.ALL) {
            if (!routing.setting(provider.code()).enabled()) {
                excluded.add(new Exclusion(provider.code(), "disabled", "switched off in your settings"));
            } else if (!provider.currencies().contains(currency)) {
                excluded.add(new Exclusion(provider.code(), "currency", "does not work in " + currency));
            } else if (!provider.countries().contains(country)) {
                excluded.add(new Exclusion(provider.code(), "country", "not available in " + country));
            } else if (amount < provider.minAmount()) {
                excluded.add(new Exclusion(provider.code(), "below_minimum",
                        "amount below " + Money.display(provider.minAmount(), currency)));
            } else if (amount > provider.maxAmount()) {
                excluded.add(new Exclusion(provider.code(), "above_maximum",
                        "amount above " + Money.display(provider.maxAmount(), currency)));
            } else if (unavailable.contains(provider.code())) {
                excluded.add(new Exclusion(provider.code(), "unavailable", "temporarily unavailable"));
            } else {
                eligible.add(provider);
            }
        }
        return new Eligibility(List.copyOf(eligible), List.copyOf(excluded));
    }

    /**
     * Ranks the providers that returned offers.
     *
     * @param offers   offers by provider code, for the providers that answered
     * @param failures why a provider that was asked could not quote (code to sentence)
     * @param stats    how each provider has been doing
     */
    public static Decision decide(MerchantRouting routing, long amount, String currency, Eligibility eligibility,
                                  Map<String, List<Offer>> offers, Map<String, String> failures,
                                  Function<String, Stats> stats) {
        List<Exclusion> excluded = new ArrayList<>(eligibility.excluded());
        List<Candidate> candidates = new ArrayList<>();
        for (ProviderInfo provider : eligibility.eligible()) {
            List<Offer> quoted = offers.getOrDefault(provider.code(), List.of());
            if (quoted.isEmpty()) {
                excluded.add(new Exclusion(provider.code(), "no_quote",
                        failures.getOrDefault(provider.code(), "did not offer anything")));
            } else {
                Offer best = quoted.stream().min(Comparator.comparingLong(Offer::merchantFee)
                        .thenComparingInt(Offer::instalmentCount)).orElseThrow();
                candidates.add(new Candidate(provider, quoted, best, stats.apply(provider.code()),
                        routing.setting(provider.code()).priority()));
            }
        }
        if (candidates.isEmpty()) {
            return new Decision(routing.mode(), null, noProviderReason(excluded, currency), List.of(), List.copyOf(excluded));
        }

        Map<String, Double> scores = score(routing, candidates);
        Comparator<Candidate> byPriority = Comparator.comparingInt(Candidate::priority)
                .thenComparingInt(c -> ProviderCatalog.position(c.provider().code()));
        Comparator<Candidate> order = switch (routing.mode()) {
            case CUSTOMER_CHOICE -> byPriority;
            case CHEAPEST -> Comparator.comparingLong((Candidate c) -> c.best().merchantFee()).thenComparing(byPriority);
            case FASTEST -> Comparator.comparingLong((Candidate c) -> c.stats().p50DecisionMs())
                    .thenComparingLong(c -> c.best().merchantFee()).thenComparing(byPriority);
            case BEST_APPROVAL -> Comparator.comparingDouble((Candidate c) -> -c.stats().approvalRate())
                    .thenComparingLong(c -> c.best().merchantFee()).thenComparing(byPriority);
            case WEIGHTED -> Comparator.comparingDouble((Candidate c) -> -scores.get(c.provider().code())).thenComparing(byPriority);
        };
        candidates.sort(order);

        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            ranked.add(new Ranked(c.provider().code(), c.provider().name(), i + 1, scores.get(c.provider().code()), c.best(),
                    c.offers().stream().sorted(Comparator.comparingInt(Offer::instalmentCount)).toList(), c.stats()));
        }
        String reason = reason(routing, amount, currency, candidates, excluded);
        return new Decision(routing.mode(), candidates.get(0).provider().code(), reason, List.copyOf(ranked), List.copyOf(excluded));
    }

    // --- scoring -----------------------------------------------------------------------------------------------

    private record Candidate(ProviderInfo provider, List<Offer> offers, Offer best, Stats stats, int priority) {
    }

    /**
     * A number from 0 to 1 for each candidate: in the weighted mode the weighted mean of how cheap, how fast and how
     * likely to approve it is next to the others; in the other modes the one thing the mode is about.
     */
    private static Map<String, Double> score(MerchantRouting routing, List<Candidate> candidates) {
        double[] cost = normalise(candidates, c -> c.best().merchantFee(), true);
        double[] speed = normalise(candidates, c -> c.stats().p50DecisionMs(), true);
        double[] approval = normalise(candidates, c -> c.stats().approvalRate(), false);
        MerchantRouting.Weights w = routing.weights();
        int total = Math.max(1, w.total());
        Map<String, Double> scores = new java.util.LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            double value = switch (routing.mode()) {
                case CHEAPEST -> cost[i];
                case FASTEST -> speed[i];
                case BEST_APPROVAL -> approval[i];
                case WEIGHTED -> (w.cost() * cost[i] + w.speed() * speed[i] + w.approval() * approval[i]) / total;
                case CUSTOMER_CHOICE -> 1.0;
            };
            scores.put(candidates.get(i).provider().code(), BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue());
        }
        return scores;
    }

    /** Scales values to 0..1, the best one to 1; when they are all equal everyone gets 1. */
    private static double[] normalise(List<Candidate> candidates, java.util.function.ToDoubleFunction<Candidate> metric,
                                      boolean lowerIsBetter) {
        double min = candidates.stream().mapToDouble(metric).min().orElse(0);
        double max = candidates.stream().mapToDouble(metric).max().orElse(0);
        double[] result = new double[candidates.size()];
        for (int i = 0; i < result.length; i++) {
            double value = metric.applyAsDouble(candidates.get(i));
            result[i] = max == min ? 1.0 : (lowerIsBetter ? (max - value) : (value - min)) / (max - min);
        }
        return result;
    }

    // --- explaining --------------------------------------------------------------------------------------------

    private static String reason(MerchantRouting routing, long amount, String currency, List<Candidate> sorted,
                                 List<Exclusion> excluded) {
        Candidate first = sorted.get(0);
        Candidate second = sorted.size() > 1 ? sorted.get(1) : null;
        String name = first.provider().name();
        String why = switch (routing.mode()) {
            case CUSTOMER_CHOICE -> "Chose " + name + ": first in your order; the customer picks from the offers shown";
            case CHEAPEST -> "Chose " + name + ": cheapest eligible (" + percent(first.best().merchantFee(), amount)
                    + (second == null ? ")" : " vs " + percent(second.best().merchantFee(), amount) + " for " + second.provider().name() + ")");
            case FASTEST -> "Chose " + name + ": fastest to decide (about " + duration(first.stats().p50DecisionMs())
                    + (second == null ? ")" : " vs " + duration(second.stats().p50DecisionMs()) + " for " + second.provider().name() + ")");
            case BEST_APPROVAL -> "Chose " + name + ": most likely to approve (" + percentage(first.stats().approvalRate())
                    + (second == null ? ")" : " vs " + percentage(second.stats().approvalRate()) + " for " + second.provider().name() + ")");
            case WEIGHTED -> "Chose " + name + ": best weighted score (cost " + routing.weights().cost() + ", speed "
                    + routing.weights().speed() + ", approval " + routing.weights().approval() + ")";
        };
        String left = excluded.stream().map(e -> providerName(e.provider()) + " excluded: " + e.message())
                .collect(Collectors.joining("; "));
        return left.isEmpty() ? why : why + "; " + left;
    }

    private static String noProviderReason(List<Exclusion> excluded, String currency) {
        String left = excluded.stream().map(e -> providerName(e.provider()) + " excluded: " + e.message())
                .collect(Collectors.joining("; "));
        return "No provider can finance this payment" + (left.isEmpty() ? "" : "; " + left);
    }

    private static String providerName(String code) {
        return ProviderCatalog.find(code).map(ProviderInfo::name).orElse(code);
    }

    /** A fee as a share of the price, such as "3.5%". */
    static String percent(long fee, long amount) {
        BigDecimal value = BigDecimal.valueOf(fee).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(amount), 1, RoundingMode.HALF_UP);
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    private static String percentage(double rate) {
        return Math.round(rate * 100) + "%";
    }

    private static String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        BigDecimal seconds = BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP).stripTrailingZeros();
        return seconds.toPlainString() + " s";
    }
}
