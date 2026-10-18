package dev.cutpay.bnpl.model;

/**
 * What a provider says about an application, in Cutpay's terms. {@code PENDING} means it has not decided yet (the
 * answer comes by callback, or has to be asked for); {@code CANCELED} means it was closed on the provider's side.
 */
public record ProviderResult(String providerRef, Outcome outcome, String declineReason) {

    public enum Outcome {
        APPROVED,
        DECLINED,
        PENDING,
        CANCELED
    }

    public static ProviderResult approved(String providerRef) {
        return new ProviderResult(providerRef, Outcome.APPROVED, null);
    }

    public static ProviderResult declined(String providerRef, String reason) {
        return new ProviderResult(providerRef, Outcome.DECLINED, reason);
    }

    public static ProviderResult pending(String providerRef) {
        return new ProviderResult(providerRef, Outcome.PENDING, null);
    }

    public static ProviderResult canceled(String providerRef) {
        return new ProviderResult(providerRef, Outcome.CANCELED, null);
    }
}
