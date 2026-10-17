package dev.cutpay.ledger.fees;

/**
 * What the fee schedule needs to know about a card. Both values come from the acquirer's BIN lookup and
 * are {@code null} for payments made before the BIN table existed; those are priced as EEA consumer cards.
 */
public record CardProfile(String type, String region) {

    public static CardProfile of(String type, String region) {
        return new CardProfile(type == null ? "consumer" : type, region == null ? "eea" : region);
    }

    public boolean isEeaConsumer() {
        return "consumer".equals(type) && "eea".equals(region);
    }
}
