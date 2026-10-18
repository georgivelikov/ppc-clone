package dev.cutpay.ledger.posting;

import java.time.Instant;
import java.util.List;

/** The parts of each inbound event the ledger cares about, already parsed. */
public final class LedgerEvents {

    private LedgerEvents() {
    }

    /** The part of a payment that goes to a connected account. */
    public record Transfer(String destination, long amount) {
    }

    /**
     * {@code transfers} are the parts sent on to connected accounts; the platform keeps the rest, minus the fee.
     * {@code bnplFee} is set when a buy-now-pay-later provider financed the payment: it is then what the provider keeps,
     * instead of a card fee, and the provider owes the rest.
     */
    public record PaymentSucceeded(String eventId, Instant occurredAt, String merchantId, boolean livemode,
                                   String paymentId, long amount, String currency, String cardType,
                                   String cardRegion, List<Transfer> transfers, Long bnplFee) {

        /** A card payment, possibly split. */
        public PaymentSucceeded(String eventId, Instant occurredAt, String merchantId, boolean livemode,
                                String paymentId, long amount, String currency, String cardType,
                                String cardRegion, List<Transfer> transfers) {
            this(eventId, occurredAt, merchantId, livemode, paymentId, amount, currency, cardType, cardRegion, transfers, null);
        }

        /** An ordinary card payment, not split. */
        public PaymentSucceeded(String eventId, Instant occurredAt, String merchantId, boolean livemode,
                                String paymentId, long amount, String currency, String cardType, String cardRegion) {
            this(eventId, occurredAt, merchantId, livemode, paymentId, amount, currency, cardType, cardRegion, List.of(), null);
        }

        public boolean isBnpl() {
            return bnplFee != null;
        }
    }

    public record RefundSucceeded(String eventId, Instant occurredAt, String merchantId, boolean livemode,
                                  String refundId, String paymentId, long amount, String currency) {
    }

    /** {@code amount} is what reaches the bank; {@code fee} is an instant-payout fee on top. */
    public record Payout(String eventId, Instant occurredAt, String merchantId, boolean livemode, String payoutId,
                         long amount, long fee, String currency) {
    }
}
