package dev.cutpay.identity.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * How risky a new business looks, from what it told us. A score at or above {@link #MANUAL_REVIEW_AT} sends the
 * application to a person at Cutpay instead of being approved by the checks alone. The rules are deliberately few and
 * readable: a real programme has hundreds, tuned over years.
 */
public final class RiskScorer {

    public static final int MANUAL_REVIEW_AT = 60;

    /** The kinds of business a merchant picks from (the plan's verticals, plus two the platform will not take on quickly). */
    public static final List<String> INDUSTRIES = List.of("retail", "hospitality", "health_beauty", "transport", "charity",
            "startup", "gambling", "crypto");

    private static final Set<String> RESTRICTED = Set.of("gambling", "crypto");
    private static final Set<String> HOME_MARKETS = Set.of("BG", "RO", "PL", "HU", "GR", "CZ", "DE", "IE");

    private RiskScorer() {
    }

    /**
     * @param monthlyVolume what the business expects to take each month, in euro cents
     * @param screening     the worst result of screening the people: {@code clear}, {@code pep} or {@code sanctions}
     */
    public record Input(String businessType, String industry, String country, long monthlyVolume, String screening,
                        boolean companyDissolved) {
    }

    public record Result(int score, List<String> reasons) {

        public boolean needsManualReview() {
            return score >= MANUAL_REVIEW_AT;
        }
    }

    public static Result score(Input in) {
        int score = 10;
        List<String> reasons = new ArrayList<>();
        if (RESTRICTED.contains(in.industry())) {
            score += 50;
            reasons.add("Restricted industry: " + in.industry());
        }
        if (!HOME_MARKETS.contains(in.country())) {
            score += 20;
            reasons.add("Outside the home markets: " + in.country());
        }
        if (in.monthlyVolume() > 100_000_000L) { // more than 1,000,000.00
            score += 60;
            reasons.add("Expects more than 1,000,000 a month");
        } else if (in.monthlyVolume() > 10_000_000L) { // more than 100,000.00
            score += 30;
            reasons.add("Expects more than 100,000 a month");
        }
        if ("sole_trader".equals(in.businessType())) {
            score += 5;
            reasons.add("Sole trader");
        }
        if ("pep".equals(in.screening())) {
            score += 40;
            reasons.add("A person is politically exposed");
        }
        if ("sanctions".equals(in.screening())) {
            score += 100;
            reasons.add("A person is on a sanctions list");
        }
        if (in.companyDissolved()) {
            score += 60;
            reasons.add("The register says the company is dissolved");
        }
        return new Result(Math.min(score, 100), reasons);
    }
}
