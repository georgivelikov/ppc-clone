package dev.cutpay.bnpl.routing;

import dev.cutpay.bnpl.model.ProviderInfo;
import java.util.List;

/** Which providers may be asked about a payment, and why each of the others may not. */
public record Eligibility(List<ProviderInfo> eligible, List<Exclusion> excluded) {

    /** A provider left out, with a short code for programs and a sentence for people. */
    public record Exclusion(String provider, String code, String message) {
    }
}
