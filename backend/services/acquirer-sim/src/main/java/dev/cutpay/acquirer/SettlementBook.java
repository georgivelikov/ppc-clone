package dev.cutpay.acquirer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * What a real acquirer sends the PSP every morning: the day's money movements as the acquirer sees them. Payments
 * and the ledger are compared with it by the reconciliation job, which is how a PSP finds the payment that was
 * charged but never recorded (or the other way round). In memory like the rest of the simulator.
 */
@Component
class SettlementBook {

    static final class Charge {
        final String ref;
        final String reference;
        final long authorized;
        final String currency;
        final Instant at;
        volatile Long captured;
        volatile boolean voided;

        Charge(String ref, String reference, long authorized, String currency, Instant at) {
            this.ref = ref;
            this.reference = reference;
            this.authorized = authorized;
            this.currency = currency;
            this.at = at;
        }

        /** What the cardholder was finally charged: nothing when released, the captured part when captured. */
        long settled() {
            return voided ? 0 : captured != null ? captured : authorized;
        }
    }

    record Refund(String refundRef, String ref, String reference, long amount, String currency, Instant at) {
    }

    /** One line of the file. */
    record Line(String type, String acquirerRef, String reference, long amount, String currency, Instant at) {
    }

    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final List<Refund> refunds = new CopyOnWriteArrayList<>();
    private final Clock clock = Clock.systemUTC();

    void authorized(String ref, String reference, long amount, String currency) {
        charges.putIfAbsent(ref, new Charge(ref, reference, amount, currency, clock.instant()));
    }

    void captured(String ref, long amount) {
        Charge charge = charges.get(ref);
        if (charge != null) {
            charge.captured = amount;
        }
    }

    void voided(String ref) {
        Charge charge = charges.get(ref);
        if (charge != null) {
            charge.voided = true;
        }
    }

    void refunded(String refundRef, String ref, long amount) {
        Charge charge = charges.get(ref);
        refunds.add(new Refund(refundRef, ref, charge == null ? null : charge.reference,
                amount, charge == null ? "" : charge.currency, clock.instant()));
    }

    /** The live charge for a payment reference, if the acquirer approved one and has not released it. */
    java.util.Optional<Charge> findByReference(String reference) {
        return charges.values().stream().filter(c -> reference.equals(c.reference) && !c.voided)
                .min(java.util.Comparator.comparing(c -> c.at));
    }

    /** The lines for a UTC day, or every line when {@code day} is null. Charges are listed at their settled amount. */
    List<Line> lines(LocalDate day) {
        List<Line> out = new ArrayList<>();
        charges.values().stream().filter(c -> in(day, c.at)).sorted(java.util.Comparator.comparing(c -> c.at))
                .forEach(c -> out.add(new Line("charge", c.ref, c.reference, c.settled(), c.currency, c.at)));
        refunds.stream().filter(r -> in(day, r.at()))
                .forEach(r -> out.add(new Line("refund", r.ref(), r.reference(), r.amount(), r.currency(), r.at())));
        return out;
    }

    private static boolean in(LocalDate day, Instant at) {
        return day == null || at.atZone(ZoneOffset.UTC).toLocalDate().equals(day);
    }

    String csv(LocalDate day) {
        StringBuilder sb = new StringBuilder("type,acquirer_ref,reference,amount,currency,at\n");
        for (Line line : lines(day)) {
            sb.append(line.type()).append(',').append(line.acquirerRef()).append(',')
                    .append(line.reference() == null ? "" : line.reference()).append(',').append(line.amount()).append(',')
                    .append(line.currency()).append(',').append(line.at()).append('\n');
        }
        return sb.toString();
    }
}
