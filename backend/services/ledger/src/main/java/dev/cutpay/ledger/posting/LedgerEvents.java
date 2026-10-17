package dev.cutpay.ledger.posting;

import java.time.Instant;

/** The parts of each inbound event the ledger cares about, already parsed. */
public final class LedgerEvents {

    private LedgerEvents() {
    }

    public record PaymentSucceeded(String eventId, Instant occurredAt, String merchantId, boolean livemode,
                                   String paymentId, long amount, String currency, String cardType,
                                   String cardRegion) {
    }

    public record RefundSucceeded(String eventId, Instant occurredAt, String merchantId, boolean livemode,
                                  String refundId, String paymentId, long amount, String currency) {
    }

    /** {@code amount} is what reaches the bank; {@code fee} is an instant-payout fee on top. */
    public record Payout(String eventId, Instant occurredAt, String merchantId, boolean livemode, String payoutId,
                         long amount, long fee, String currency) {
    }
}
