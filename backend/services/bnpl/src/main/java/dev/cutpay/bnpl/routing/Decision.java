package dev.cutpay.bnpl.routing;

import dev.cutpay.bnpl.model.Offer;
import dev.cutpay.bnpl.routing.Eligibility.Exclusion;
import java.util.List;

/**
 * What the engine decided and why. {@code ranked} is every provider that could finance the payment, best first; each
 * carries its offers. {@code chosen} is null when nobody can.
 */
public record Decision(RoutingMode mode, String chosen, String reason, List<Ranked> ranked, List<Exclusion> excluded) {

    /** @param best the offer that costs the merchant least, the one the provider is judged by */
    public record Ranked(String provider, String name, int rank, double score, Offer best, List<Offer> offers, Stats stats) {
    }
}
