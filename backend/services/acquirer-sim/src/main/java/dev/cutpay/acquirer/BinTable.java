package dev.cutpay.acquirer;

import java.util.Map;

/**
 * A tiny stand-in for an issuer BIN database. The first six digits of a card number decide whether the
 * card is a consumer or business card and whether it was issued inside the EEA; the fee schedule in the
 * ledger depends on it (docs/plans/phase-02-ledger-payouts.md). Unknown BINs are EEA consumer cards.
 */
final class BinTable {

    record BinInfo(String cardType, String cardRegion) {
    }

    static final BinInfo DEFAULT = new BinInfo("consumer", "eea");

    private static final Map<String, BinInfo> BY_BIN = Map.of(
            "400001", new BinInfo("business", "eea"),
            "400002", new BinInfo("consumer", "non_eea"),
            "400003", new BinInfo("business", "non_eea"));

    private BinTable() {
    }

    static BinInfo lookup(String pan) {
        if (pan == null || pan.length() < 6) {
            return DEFAULT;
        }
        return BY_BIN.getOrDefault(pan.substring(0, 6), DEFAULT);
    }
}
