package dev.cutpay.ledger.posting;

import static dev.cutpay.ledger.posting.AccountType.BNPL_RECEIVABLE;
import static dev.cutpay.ledger.posting.AccountType.CUSTOMER_FUNDS;
import static dev.cutpay.ledger.posting.AccountType.DISPUTES_HELD;
import static dev.cutpay.ledger.posting.AccountType.MERCHANT_AVAILABLE;
import static dev.cutpay.ledger.posting.AccountType.MERCHANT_PENDING;
import static dev.cutpay.ledger.posting.AccountType.MERCHANT_RESERVE;
import static dev.cutpay.ledger.posting.AccountType.PAYOUTS_IN_TRANSIT;
import static dev.cutpay.ledger.posting.AccountType.PLATFORM_CASH;
import static dev.cutpay.ledger.posting.AccountType.PLATFORM_FEES;

import dev.cutpay.events.InboxGuard;
import dev.cutpay.events.OutboxWriter;
import dev.cutpay.ledger.config.Topics;
import dev.cutpay.ledger.fees.CardProfile;
import dev.cutpay.ledger.fees.PricingPlan;
import dev.cutpay.ledger.fees.PricingPlans;
import dev.cutpay.ledger.fx.FxService;
import dev.cutpay.ledger.posting.LedgerEvents.BnplSettled;
import dev.cutpay.ledger.posting.LedgerEvents.DisputeClosed;
import dev.cutpay.ledger.posting.LedgerEvents.DisputeOpened;
import dev.cutpay.ledger.posting.LedgerEvents.Payout;
import dev.cutpay.ledger.posting.LedgerEvents.PaymentSucceeded;
import dev.cutpay.ledger.posting.LedgerEvents.RefundSucceeded;
import dev.cutpay.ledger.posting.LedgerEvents.Transfer;
import dev.cutpay.ledger.posting.PostingEngine.Entry;
import dev.cutpay.ledger.posting.PostingEngine.Line;
import dev.cutpay.web.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns business events into journal entries (docs/plans/phase-02-ledger-payouts.md).
 *
 * <p>Every {@code on...} method is one transaction that (1) records the event in the inbox, (2) writes the
 * postings and derived rows and (3) writes the follow-up events to the outbox. A redelivered event finds
 * itself in the inbox and does nothing, which is what makes replaying a topic from offset 0 safe.
 *
 * <p>Sign convention: debit positive, credit negative. Merchant balances are therefore negative numbers
 * internally.
 */
@Service
public class LedgerService {

    private final PostingEngine engine;
    private final Accounts accounts;
    private final PricingPlans plans;
    private final InboxGuard inbox;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final FxService fx;

    public LedgerService(PostingEngine engine, Accounts accounts, PricingPlans plans, InboxGuard inbox,
                         OutboxWriter outbox, JdbcClient jdbc, Clock clock, FxService fx) {
        this.fx = fx;
        this.engine = engine;
        this.accounts = accounts;
        this.plans = plans;
        this.inbox = inbox;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // --- merchant ----------------------------------------------------------------------------------

    @Transactional
    public void onMerchantCreated(String eventId, String merchantId, String defaultCurrency) {
        if (!inbox.firstTime(eventId)) {
            return;
        }
        plans.createStandardIfMissing(merchantId);
        fx.createSettingsIfMissing(merchantId, defaultCurrency);
        for (boolean livemode : new boolean[] {false, true}) {
            accounts.merchant(merchantId, livemode, MERCHANT_PENDING, defaultCurrency);
            accounts.merchant(merchantId, livemode, MERCHANT_AVAILABLE, defaultCurrency);
            accounts.merchant(merchantId, livemode, MERCHANT_RESERVE, defaultCurrency);
        }
    }

    /** Remembers whose connected account it is, so only that platform can look at its balance. */
    @Transactional
    public void onConnectedAccountCreated(String eventId, String accountId, String platformId, boolean livemode) {
        if (!inbox.firstTime(eventId)) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO connected_accounts (account_id, platform_id, livemode)
                        VALUES (:account, :platform, :livemode) ON CONFLICT (account_id) DO NOTHING
                        """)
                .param("account", accountId)
                .param("platform", platformId)
                .param("livemode", livemode)
                .update();
    }

    @Transactional
    public void onPayoutScheduleChanged(String eventId, String merchantId, int delayDays) {
        if (!inbox.firstTime(eventId)) {
            return;
        }
        // Applies to payments from now on; payments already pending keep their release date.
        plans.setDelayDays(merchantId, delayDays);
    }

    // --- payments ----------------------------------------------------------------------------------

    /**
     * Customer funds (debit) are shared between the merchant's pending balance, the platform's fee and, for a split
     * payment, the pending balance of each connected account (credits). The merchant keeps what the transfers and
     * the fee leave, so a platform that sends the whole amount on pays the fee out of its own balance.
     */
    @Transactional
    public void onPaymentSucceeded(PaymentSucceeded p) {
        if (!inbox.firstTime(p.eventId())) {
            return;
        }
        PricingPlan plan = plans.findOrCreate(p.merchantId());
        long fee = p.isBnpl() ? p.bnplFee()
                : p.isBlik() ? PricingPlan.blikFee(p.amount())
                : plan.strategy().feeFor(p.amount(), CardProfile.of(p.cardType(), p.cardRegion()));
        if (p.isBnpl() && (p.bnplFee() < 0 || p.bnplFee() > p.amount() || !p.transfers().isEmpty())) {
            throw new IllegalArgumentException("Payment " + p.paymentId() + " has an impossible BNPL fee or is split");
        }
        long sent = p.transfers().stream().mapToLong(Transfer::amount).sum();
        if (sent > p.amount()) {
            throw new IllegalArgumentException("Payment " + p.paymentId() + " sends more than it is worth");
        }
        long net = p.amount() - fee - sent;

        List<Line> lines = new ArrayList<>();
        if (p.isBnpl()) {
            // The provider owes the price less its own fee; that fee is the merchant's cost, not platform revenue.
            lines.add(new Line(accounts.platform(p.livemode(), BNPL_RECEIVABLE, p.currency()), p.amount() - fee));
        } else {
            lines.add(new Line(accounts.platform(p.livemode(), CUSTOMER_FUNDS, p.currency()), p.amount()));
            lines.add(new Line(accounts.platform(p.livemode(), PLATFORM_FEES, p.currency()), -fee));
        }
        lines.add(new Line(accounts.merchant(p.merchantId(), p.livemode(), MERCHANT_PENDING, p.currency()), -net));
        for (Transfer transfer : p.transfers()) {
            lines.add(new Line(accounts.merchant(transfer.destination(), p.livemode(), MERCHANT_PENDING, p.currency()),
                    -transfer.amount()));
        }

        Optional<String> entryId = engine.post(new Entry(p.eventId(), "payment", "Payment " + p.paymentId(),
                p.merchantId(), p.livemode(), p.currency(), p.paymentId(), p.occurredAt(), lines));
        if (entryId.isEmpty()) {
            return;
        }
        Instant availableOn = p.occurredAt().plus(Duration.ofDays(plan.delayDays()));
        insertBalanceTransaction(p.merchantId(), p.livemode(), p.currency(), "payment", p.paymentId(), null,
                p.amount(), fee, net, "Payment " + p.paymentId(), entryId.get(), availableOn, p.occurredAt());
        insertPendingRelease(p.paymentId(), p.merchantId(), p.livemode(), p.currency(), net, availableOn);
        emitPosted(entryId.get(), "payment", p.paymentId(), p.merchantId(), p.livemode(), p.currency(), net);
        if (p.isBnpl()) {
            jdbc.sql("""
                            INSERT INTO bnpl_payments (payment_id, merchant_id, livemode, currency, gross, fee)
                            VALUES (:payment, :merchant, :livemode, :currency, :gross, :fee)
                            ON CONFLICT (payment_id) DO NOTHING
                            """)
                    .param("payment", p.paymentId()).param("merchant", p.merchantId()).param("livemode", p.livemode())
                    .param("currency", p.currency()).param("gross", p.amount()).param("fee", fee).update();
        }

        for (Transfer transfer : p.transfers()) {
            jdbc.sql("""
                            INSERT INTO payment_transfers (payment_id, destination, platform_id, livemode, currency, gross,
                                                           amount)
                            VALUES (:payment, :destination, :platform, :livemode, :currency, :gross, :amount)
                            ON CONFLICT (payment_id, destination) DO NOTHING
                            """)
                    .param("payment", p.paymentId())
                    .param("destination", transfer.destination())
                    .param("platform", p.merchantId())
                    .param("livemode", p.livemode())
                    .param("currency", p.currency())
                    .param("gross", p.amount())
                    .param("amount", transfer.amount())
                    .update();
            insertBalanceTransaction(transfer.destination(), p.livemode(), p.currency(), "transfer", p.paymentId(), null,
                    transfer.amount(), 0, transfer.amount(), "Transfer from payment " + p.paymentId(), entryId.get(),
                    availableOn, p.occurredAt());
            insertPendingRelease(p.paymentId(), transfer.destination(), p.livemode(), p.currency(), transfer.amount(),
                    availableOn);
            emitPosted(entryId.get(), "transfer", p.paymentId(), transfer.destination(), p.livemode(), p.currency(),
                    transfer.amount());
        }
    }

    private void insertPendingRelease(String paymentId, String merchantId, boolean livemode, String currency,
                                      long remaining, Instant availableOn) {
        jdbc.sql("""
                        INSERT INTO pending_releases (payment_id, merchant_id, livemode, currency, remaining,
                                                      available_at)
                        VALUES (:payment, :merchant, :livemode, :currency, :remaining, :availableAt)
                        ON CONFLICT (payment_id, merchant_id) DO NOTHING
                        """)
                .param("payment", paymentId)
                .param("merchant", merchantId)
                .param("livemode", livemode)
                .param("currency", currency)
                .param("remaining", remaining)
                .param("availableAt", Sql.ts(availableOn))
                .update();
    }

    /**
     * A refund returns money to the customer and comes out of the merchant's balance; the fee is not
     * returned. If the payment's funds are still pending, the refund is taken from there (and the amount
     * still to be released shrinks). Otherwise it is taken from the available balance, which may then
     * turn negative and is deducted from the next payout.
     *
     * <p>A split payment is refunded in proportion: each connected account gives back its share of the
     * refund (rounded half up, never more than it received) and the platform covers the rest.
     */
    @Transactional
    public void onRefundSucceeded(RefundSucceeded r) {
        if (!inbox.firstTime(r.eventId())) {
            return;
        }
        // Fails (and is retried) when the payment has not reached the ledger yet.
        jdbc.sql("""
                        SELECT released_at IS NOT NULL FROM pending_releases
                         WHERE payment_id = :payment AND merchant_id = :merchant FOR UPDATE
                        """)
                .param("payment", r.paymentId())
                .param("merchant", r.merchantId())
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Payment " + r.paymentId() + " is not in the ledger yet; refund " + r.refundId()
                                + " will be retried"));

        var financed = jdbc.sql("SELECT gross, fee, refunded_gross, refunded_net, settled_at IS NOT NULL AS settled FROM bnpl_payments WHERE payment_id = :payment FOR UPDATE")
                .param("payment", r.paymentId())
                .query((rs, n) -> new BnplPayment(rs.getLong("gross"), rs.getLong("fee"), rs.getLong("refunded_gross"),
                        rs.getLong("refunded_net"), rs.getBoolean("settled")))
                .optional();
        if (financed.isPresent()) {
            refundBnpl(r, financed.get());
            return;
        }

        record Share(String destination, long amount, long reversed, long gross) {
        }
        List<Share> shares = jdbc.sql("""
                        SELECT destination, amount, reversed, gross FROM payment_transfers
                         WHERE payment_id = :payment ORDER BY destination FOR UPDATE
                        """)
                .param("payment", r.paymentId())
                .query((rs, n) -> new Share(rs.getString("destination"), rs.getLong("amount"), rs.getLong("reversed"),
                        rs.getLong("gross")))
                .list();
        Map<String, Long> taken = new LinkedHashMap<>();
        long budget = r.amount();
        for (Share share : shares) {
            long proportional = BigDecimal.valueOf(share.amount()).multiply(BigDecimal.valueOf(r.amount()))
                    .divide(BigDecimal.valueOf(share.gross()), 0, RoundingMode.HALF_UP).longValueExact();
            long reversal = Math.min(Math.min(proportional, share.amount() - share.reversed()), budget);
            budget -= reversal;
            taken.put(share.destination(), reversal);
        }
        long platformShare = r.amount() - taken.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Long> bySource = new LinkedHashMap<>();
        bySource.put(r.merchantId(), platformShare);
        bySource.putAll(taken);
        Map<String, Boolean> released = new LinkedHashMap<>();
        List<Line> lines = new ArrayList<>();
        for (var party : bySource.entrySet()) {
            boolean isReleased = Boolean.TRUE.equals(jdbc.sql("""
                            SELECT released_at IS NOT NULL FROM pending_releases
                             WHERE payment_id = :payment AND merchant_id = :merchant FOR UPDATE
                            """)
                    .param("payment", r.paymentId())
                    .param("merchant", party.getKey())
                    .query(Boolean.class).optional().orElse(false));
            released.put(party.getKey(), isReleased);
            AccountType source = isReleased ? MERCHANT_AVAILABLE : MERCHANT_PENDING;
            lines.add(new Line(accounts.merchant(party.getKey(), r.livemode(), source, r.currency()), party.getValue()));
        }
        lines.add(new Line(accounts.platform(r.livemode(), CUSTOMER_FUNDS, r.currency()), -r.amount()));
        Optional<String> entryId = engine.post(new Entry(r.eventId(), "refund", "Refund " + r.refundId(),
                r.merchantId(), r.livemode(), r.currency(), r.refundId(), r.occurredAt(), lines));
        if (entryId.isEmpty()) {
            return;
        }
        insertBalanceTransaction(r.merchantId(), r.livemode(), r.currency(), "refund", r.refundId(), r.paymentId(),
                -r.amount(), 0, -platformShare, "Refund " + r.refundId() + " of " + r.paymentId(), entryId.get(), null,
                r.occurredAt());
        for (var party : bySource.entrySet()) {
            boolean platform = party.getKey().equals(r.merchantId());
            long amount = party.getValue();
            if (!platform && amount > 0) {
                insertBalanceTransaction(party.getKey(), r.livemode(), r.currency(), "transfer_reversal", r.refundId(),
                        r.paymentId(), -amount, 0, -amount,
                        "Refund " + r.refundId() + " of " + r.paymentId() + " taken back", entryId.get(), null,
                        r.occurredAt());
                jdbc.sql("UPDATE payment_transfers SET reversed = reversed + :amount WHERE payment_id = :payment AND destination = :destination")
                        .param("amount", amount).param("payment", r.paymentId()).param("destination", party.getKey())
                        .update();
            }
            if (amount == 0) {
                continue;
            }
            if (released.get(party.getKey())) {
                // Several parties can share one entry, and payouts keys its rows on the entry, so qualify it.
                emitFundsAvailable(platform ? entryId.get() : entryId.get() + "#" + party.getKey(), r.refundId(),
                        party.getKey(), r.livemode(), r.currency(), -amount, "refund");
            } else {
                jdbc.sql("UPDATE pending_releases SET remaining = remaining - :amount WHERE payment_id = :payment AND merchant_id = :merchant")
                        .param("amount", amount)
                        .param("payment", r.paymentId())
                        .param("merchant", party.getKey())
                        .update();
            }
            emitPosted(entryId.get(), "refund", r.refundId(), party.getKey(), r.livemode(), r.currency(), -amount);
        }
    }

    /** What the ledger keeps about a payment a provider financed. */
    private record BnplPayment(long gross, long fee, long refundedGross, long refundedNet, boolean settled) {
    }

    /**
     * A refund of a payment financed in instalments. The provider gives the customer the refund and takes back the same
     * share of its fee, so the merchant gives back only the share of the price they actually received. Taking the shares
     * from the running total makes the pieces add up exactly: refunding everything returns the whole of price less fee.
     * The provider owes the platform that much less if it has not paid yet; if it has, the platform pays it back.
     */
    private void refundBnpl(RefundSucceeded r, BnplPayment b) {
        long received = b.gross() - b.fee();
        long refundedGross = b.refundedGross() + r.amount();
        if (refundedGross > b.gross()) {
            throw new IllegalArgumentException("Refund " + r.refundId() + " is more than the payment " + r.paymentId());
        }
        long refundedNet = BigDecimal.valueOf(refundedGross).multiply(BigDecimal.valueOf(received))
                .divide(BigDecimal.valueOf(b.gross()), 0, RoundingMode.HALF_UP).longValueExact();
        long net = refundedNet - b.refundedNet();
        jdbc.sql("UPDATE bnpl_payments SET refunded_gross = :gross, refunded_net = :net WHERE payment_id = :payment")
                .param("gross", refundedGross).param("net", refundedNet).param("payment", r.paymentId()).update();
        if (net == 0) {
            return; // so small that the share of the merchant rounds to nothing
        }
        boolean released = Boolean.TRUE.equals(jdbc.sql("SELECT released_at IS NOT NULL FROM pending_releases WHERE payment_id = :payment AND merchant_id = :merchant")
                .param("payment", r.paymentId()).param("merchant", r.merchantId()).query(Boolean.class).optional().orElse(false));
        AccountType owedBy = released ? MERCHANT_AVAILABLE : MERCHANT_PENDING;
        AccountType paidFrom = b.settled() ? CUSTOMER_FUNDS : BNPL_RECEIVABLE;
        Optional<String> entryId = engine.post(new Entry(r.eventId(), "refund", "Refund " + r.refundId(), r.merchantId(),
                r.livemode(), r.currency(), r.refundId(), r.occurredAt(),
                List.of(new Line(accounts.merchant(r.merchantId(), r.livemode(), owedBy, r.currency()), net),
                        new Line(accounts.platform(r.livemode(), paidFrom, r.currency()), -net))));
        if (entryId.isEmpty()) {
            return;
        }
        // The fee share that comes back shows as a negative fee: gross - fee = net.
        insertBalanceTransaction(r.merchantId(), r.livemode(), r.currency(), "refund", r.refundId(), r.paymentId(),
                -r.amount(), -(r.amount() - net), -net, "Refund " + r.refundId() + " of " + r.paymentId(), entryId.get(),
                null, r.occurredAt());
        if (released) {
            emitFundsAvailable(entryId.get(), r.refundId(), r.merchantId(), r.livemode(), r.currency(), -net, "refund");
        } else {
            jdbc.sql("UPDATE pending_releases SET remaining = remaining - :amount WHERE payment_id = :payment AND merchant_id = :merchant")
                    .param("amount", net).param("payment", r.paymentId()).param("merchant", r.merchantId()).update();
        }
        emitPosted(entryId.get(), "refund", r.refundId(), r.merchantId(), r.livemode(), r.currency(), -net);
    }

    /**
     * The provider has paid the platform: what it still owed (the price less its fee less what was refunded) moves from the
     * receivable to the platform's funds. Refunds from now on are paid from those funds.
     */
    @Transactional
    public void onBnplSettled(BnplSettled s) {
        if (!inbox.firstTime(s.eventId())) {
            return;
        }
        var financed = jdbc.sql("SELECT gross, fee, refunded_gross, refunded_net, settled_at IS NOT NULL AS settled FROM bnpl_payments WHERE payment_id = :payment FOR UPDATE")
                .param("payment", s.paymentId())
                .query((rs, n) -> new BnplPayment(rs.getLong("gross"), rs.getLong("fee"), rs.getLong("refunded_gross"),
                        rs.getLong("refunded_net"), rs.getBoolean("settled")))
                .optional()
                // Fails (and is retried) when the payment has not reached the ledger yet.
                .orElseThrow(() -> new IllegalStateException("Payment " + s.paymentId() + " is not in the ledger yet; "
                        + "its settlement will be retried"));
        if (financed.settled()) {
            return;
        }
        String currency = jdbc.sql("SELECT currency FROM bnpl_payments WHERE payment_id = :payment")
                .param("payment", s.paymentId()).query(String.class).single().trim();
        long owed = financed.gross() - financed.fee() - financed.refundedNet();
        if (owed > 0) {
            engine.post(new Entry(s.eventId(), "bnpl_settlement", "Settlement of " + s.paymentId(), s.merchantId(), s.livemode(),
                    currency, s.paymentId(), s.occurredAt(),
                    List.of(new Line(accounts.platform(s.livemode(), CUSTOMER_FUNDS, currency), owed),
                            new Line(accounts.platform(s.livemode(), BNPL_RECEIVABLE, currency), -owed))));
        }
        jdbc.sql("UPDATE bnpl_payments SET settled_at = :at, settled_amount = :amount WHERE payment_id = :payment")
                .param("at", Sql.ts(s.occurredAt())).param("amount", owed).param("payment", s.paymentId()).update();
    }

    // --- availability (T+N) -----------------------------------------------------------------------

    /**
     * Moves funds whose waiting period has passed from pending to available. Runs in its own transaction
     * per batch so a long backlog does not hold locks for long.
     *
     * @return how many payments were released in this batch
     */
    @Transactional
    public int releaseDueFunds(int limit) {
        Instant now = clock.instant();
        record Due(String paymentId, String merchantId, boolean livemode, String currency, long remaining) {
        }
        List<Due> due = jdbc.sql("""
                        SELECT payment_id, merchant_id, livemode, currency, remaining FROM pending_releases
                         WHERE released_at IS NULL AND available_at <= :now
                         ORDER BY available_at, payment_id
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("now", Sql.ts(now))
                .param("limit", limit)
                .query((rs, n) -> new Due(rs.getString("payment_id"), rs.getString("merchant_id"),
                        rs.getBoolean("livemode"), rs.getString("currency"), rs.getLong("remaining")))
                .list();

        for (Due d : due) {
            jdbc.sql("UPDATE pending_releases SET released_at = :now WHERE payment_id = :payment AND merchant_id = :merchant")
                    .param("now", Sql.ts(now))
                    .param("payment", d.paymentId())
                    .param("merchant", d.merchantId())
                    .update();
            if (d.remaining() == 0) {
                continue; // fully refunded before it ever became available
            }
            List<Line> lines = List.of(
                    new Line(accounts.merchant(d.merchantId(), d.livemode(), MERCHANT_PENDING, d.currency()),
                            d.remaining()),
                    new Line(accounts.merchant(d.merchantId(), d.livemode(), MERCHANT_AVAILABLE, d.currency()),
                            -d.remaining()));
            // One release per payment, so the event id is derived from it and a retry cannot post twice.
            Optional<String> entryId = engine.post(new Entry("avail_" + d.paymentId() + ":" + d.merchantId(), "funds_available",
                    "Funds available for " + d.paymentId(), d.merchantId(), d.livemode(), d.currency(),
                    d.paymentId(), now, lines));
            entryId.ifPresent(id -> {
                emitFundsAvailable(id, d.paymentId(), d.merchantId(), d.livemode(), d.currency(), d.remaining(),
                        "release");
                emitPosted(id, "funds_available", d.paymentId(), d.merchantId(), d.livemode(), d.currency(), 0);
            });
            // A merchant who settles in another currency has these funds converted as they become available.
            fx.settleInto(d.merchantId(), d.livemode(), d.currency(), d.remaining(), d.paymentId());
        }
        return due.size();
    }

    // --- manual adjustments -----------------------------------------------------------------------

    /**
     * An approved correction by staff. A positive amount pays the merchant out of the platform's own cash, a negative one takes
     * it back. Posted once per adjustment (the id is the event id), into available funds.
     */
    @Transactional
    public void postAdjustment(String adjustmentId, String merchantId, boolean livemode, String currency, long amount, String reason) {
        Instant now = clock.instant();
        List<Line> lines = List.of(
                new Line(accounts.merchant(merchantId, livemode, MERCHANT_AVAILABLE, currency), -amount),
                new Line(accounts.platform(livemode, PLATFORM_CASH, currency), amount));
        Optional<String> entryId = engine.post(new Entry("adj_" + adjustmentId, "adjustment", "Adjustment: " + reason, merchantId,
                livemode, currency, adjustmentId, now, lines));
        entryId.ifPresent(id -> {
            insertBalanceTransaction(merchantId, livemode, currency, "adjustment", adjustmentId, null, amount, 0, amount,
                    "Adjustment: " + reason, id, null, now);
            emitFundsAvailable(id, adjustmentId, merchantId, livemode, currency, amount, "adjustment");
            emitPosted(id, "adjustment", adjustmentId, merchantId, livemode, currency, amount);
        });
    }

    // --- disputes ---------------------------------------------------------------------------------

    /**
     * A cardholder disputed a payment. The disputed amount leaves the merchant's balance for the disputes account, from
     * pending funds if the payment has not been released yet, otherwise from available; the fee comes out of available
     * funds, which may leave them negative until later payments cover it.
     */
    @Transactional
    public void onDisputeOpened(DisputeOpened d) {
        if (!inbox.firstTime(d.eventId())) {
            return;
        }
        boolean released = jdbc.sql("""
                        SELECT released_at IS NOT NULL FROM pending_releases
                         WHERE payment_id = :payment AND merchant_id = :merchant FOR UPDATE
                        """)
                .param("payment", d.paymentId()).param("merchant", d.merchantId())
                .query(Boolean.class).optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Payment " + d.paymentId() + " is not in the ledger yet; dispute " + d.disputeId() + " will be retried"));
        AccountType source = released ? MERCHANT_AVAILABLE : MERCHANT_PENDING;
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(accounts.merchant(d.merchantId(), d.livemode(), source, d.currency()), d.amount()));
        lines.add(new Line(accounts.platform(d.livemode(), DISPUTES_HELD, d.currency()), -d.amount()));
        lines.add(new Line(accounts.merchant(d.merchantId(), d.livemode(), MERCHANT_AVAILABLE, d.currency()), d.fee()));
        lines.add(new Line(accounts.platform(d.livemode(), PLATFORM_FEES, d.currency()), -d.fee()));
        Optional<String> entryId = engine.post(new Entry(d.eventId(), "dispute_opened", "Dispute " + d.disputeId(),
                d.merchantId(), d.livemode(), d.currency(), d.disputeId(), d.occurredAt(), lines));
        if (entryId.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO ledger_disputes (dispute_id, merchant_id, livemode, payment_id, currency, amount, fee)
                        VALUES (:id, :merchant, :livemode, :payment, :currency, :amount, :fee)
                        """)
                .param("id", d.disputeId()).param("merchant", d.merchantId()).param("livemode", d.livemode())
                .param("payment", d.paymentId()).param("currency", d.currency()).param("amount", d.amount())
                .param("fee", d.fee()).update();
        insertBalanceTransaction(d.merchantId(), d.livemode(), d.currency(), "dispute", d.disputeId(), d.paymentId(),
                -d.amount(), 0, -d.amount(), "Dispute " + d.disputeId() + " of " + d.paymentId(), entryId.get(), null,
                d.occurredAt());
        insertBalanceTransaction(d.merchantId(), d.livemode(), d.currency(), "dispute_fee", d.disputeId(), d.paymentId(),
                -d.fee(), 0, -d.fee(), "Fee for dispute " + d.disputeId(), entryId.get(), null, d.occurredAt());
        if (released) {
            emitFundsAvailable(entryId.get() + "#hold", d.disputeId(), d.merchantId(), d.livemode(), d.currency(),
                    -d.amount(), "dispute");
        } else {
            jdbc.sql("UPDATE pending_releases SET remaining = remaining - :amount WHERE payment_id = :payment AND merchant_id = :merchant")
                    .param("amount", d.amount()).param("payment", d.paymentId()).param("merchant", d.merchantId()).update();
        }
        emitFundsAvailable(entryId.get() + "#fee", d.disputeId(), d.merchantId(), d.livemode(), d.currency(), -d.fee(),
                "dispute_fee");
        emitPosted(entryId.get(), "dispute_opened", d.disputeId(), d.merchantId(), d.livemode(), d.currency(),
                -d.amount() - d.fee());
    }

    /** Won: the amount and the fee go back to the merchant. Lost: the amount goes to the customer, the fee stays. */
    @Transactional
    public void onDisputeClosed(DisputeClosed c) {
        if (!inbox.firstTime(c.eventId())) {
            return;
        }
        record Held(String paymentId, String currency, long amount, long fee, boolean closed) {
        }
        Held held = jdbc.sql("SELECT payment_id, currency, amount, fee, closed_at IS NOT NULL FROM ledger_disputes WHERE dispute_id = :id FOR UPDATE")
                .param("id", c.disputeId())
                .query((rs, n) -> new Held(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getBoolean(5)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Dispute " + c.disputeId() + " is not in the ledger yet; it will be retried"));
        if (held.closed()) {
            return;
        }
        boolean released = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT released_at IS NOT NULL FROM pending_releases
                         WHERE payment_id = :payment AND merchant_id = :merchant FOR UPDATE
                        """)
                .param("payment", held.paymentId()).param("merchant", c.merchantId())
                .query(Boolean.class).optional().orElse(true));
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(accounts.platform(c.livemode(), DISPUTES_HELD, held.currency()), held.amount()));
        if (c.won()) {
            AccountType back = released ? MERCHANT_AVAILABLE : MERCHANT_PENDING;
            lines.add(new Line(accounts.merchant(c.merchantId(), c.livemode(), back, held.currency()), -held.amount()));
            lines.add(new Line(accounts.platform(c.livemode(), PLATFORM_FEES, held.currency()), held.fee()));
            lines.add(new Line(accounts.merchant(c.merchantId(), c.livemode(), MERCHANT_AVAILABLE, held.currency()), -held.fee()));
        } else {
            lines.add(new Line(accounts.platform(c.livemode(), CUSTOMER_FUNDS, held.currency()), -held.amount()));
        }
        Optional<String> entryId = engine.post(new Entry(c.eventId(), c.won() ? "dispute_won" : "dispute_lost",
                "Dispute " + c.disputeId() + (c.won() ? " won" : " lost"), c.merchantId(), c.livemode(), held.currency(),
                c.disputeId(), c.occurredAt(), lines));
        jdbc.sql("UPDATE ledger_disputes SET closed_at = :now WHERE dispute_id = :id")
                .param("now", Sql.ts(c.occurredAt())).param("id", c.disputeId()).update();
        if (entryId.isEmpty() || !c.won()) {
            return;
        }
        insertBalanceTransaction(c.merchantId(), c.livemode(), held.currency(), "dispute_reversal", c.disputeId(),
                held.paymentId(), held.amount(), 0, held.amount(), "Dispute " + c.disputeId() + " won", entryId.get(), null,
                c.occurredAt());
        insertBalanceTransaction(c.merchantId(), c.livemode(), held.currency(), "dispute_fee_reversal", c.disputeId(),
                held.paymentId(), held.fee(), 0, held.fee(), "Fee for dispute " + c.disputeId() + " returned", entryId.get(),
                null, c.occurredAt());
        if (released) {
            emitFundsAvailable(entryId.get() + "#hold", c.disputeId(), c.merchantId(), c.livemode(), held.currency(),
                    held.amount(), "dispute_won");
        } else {
            jdbc.sql("UPDATE pending_releases SET remaining = remaining + :amount WHERE payment_id = :payment AND merchant_id = :merchant")
                    .param("amount", held.amount()).param("payment", held.paymentId()).param("merchant", c.merchantId()).update();
        }
        emitFundsAvailable(entryId.get() + "#fee", c.disputeId(), c.merchantId(), c.livemode(), held.currency(), held.fee(),
                "dispute_fee_returned");
        emitPosted(entryId.get(), "dispute_won", c.disputeId(), c.merchantId(), c.livemode(), held.currency(),
                held.amount() + held.fee());
    }

    // --- payouts ----------------------------------------------------------------------------------

    /** Available balance leaves the merchant: into transit for the bank, minus any instant-payout fee. */
    @Transactional
    public void onPayoutCreated(Payout p) {
        if (!inbox.firstTime(p.eventId())) {
            return;
        }
        List<Line> lines = List.of(
                new Line(accounts.merchant(p.merchantId(), p.livemode(), MERCHANT_AVAILABLE, p.currency()),
                        p.amount() + p.fee()),
                new Line(accounts.platform(p.livemode(), PAYOUTS_IN_TRANSIT, p.currency()), -p.amount()),
                new Line(accounts.platform(p.livemode(), PLATFORM_FEES, p.currency()), -p.fee()));
        Optional<String> entryId = engine.post(new Entry(p.eventId(), "payout_created", "Payout " + p.payoutId(),
                p.merchantId(), p.livemode(), p.currency(), p.payoutId(), p.occurredAt(), lines));
        entryId.ifPresent(id -> {
            insertBalanceTransaction(p.merchantId(), p.livemode(), p.currency(), "payout", p.payoutId(), null,
                    -p.amount(), p.fee(), -(p.amount() + p.fee()), "Payout " + p.payoutId(), id, null,
                    p.occurredAt());
            emitPosted(id, "payout_created", p.payoutId(), p.merchantId(), p.livemode(), p.currency(),
                    -(p.amount() + p.fee()));
        });
    }

    /** The bank confirmed: the money has left the platform. */
    @Transactional
    public void onPayoutPaid(Payout p) {
        if (!inbox.firstTime(p.eventId())) {
            return;
        }
        List<Line> lines = List.of(
                new Line(accounts.platform(p.livemode(), PAYOUTS_IN_TRANSIT, p.currency()), p.amount()),
                new Line(accounts.platform(p.livemode(), PLATFORM_CASH, p.currency()), -p.amount()));
        engine.post(new Entry(p.eventId(), "payout_paid", "Payout " + p.payoutId() + " paid", p.merchantId(),
                p.livemode(), p.currency(), p.payoutId(), p.occurredAt(), lines))
                .ifPresent(id -> emitPosted(id, "payout_paid", p.payoutId(), p.merchantId(), p.livemode(),
                        p.currency(), 0));
    }

    /** The bank rejected the transfer: the funds (and any fee) go back to the merchant's available balance. */
    @Transactional
    public void onPayoutFailed(Payout p) {
        if (!inbox.firstTime(p.eventId())) {
            return;
        }
        List<Line> lines = List.of(
                new Line(accounts.platform(p.livemode(), PAYOUTS_IN_TRANSIT, p.currency()), p.amount()),
                new Line(accounts.merchant(p.merchantId(), p.livemode(), MERCHANT_AVAILABLE, p.currency()),
                        -(p.amount() + p.fee())),
                new Line(accounts.platform(p.livemode(), PLATFORM_FEES, p.currency()), p.fee()));
        engine.post(new Entry(p.eventId(), "payout_failed", "Payout " + p.payoutId() + " failed", p.merchantId(),
                p.livemode(), p.currency(), p.payoutId(), p.occurredAt(), lines)).ifPresent(id -> {
                    insertBalanceTransaction(p.merchantId(), p.livemode(), p.currency(), "payout_failure",
                            p.payoutId(), null, p.amount(), -p.fee(), p.amount() + p.fee(),
                            "Payout " + p.payoutId() + " failed", id, null, p.occurredAt());
                    emitFundsAvailable(id, p.payoutId(), p.merchantId(), p.livemode(), p.currency(),
                            p.amount() + p.fee(), "payout_failed");
                    emitPosted(id, "payout_failed", p.payoutId(), p.merchantId(), p.livemode(), p.currency(),
                            p.amount() + p.fee());
                });
    }

    // --- helpers ----------------------------------------------------------------------------------

    private void insertBalanceTransaction(String merchantId, boolean livemode, String currency, String type,
                                          String sourceId, String parentId, long gross, long fee, long net,
                                          String description, String journalEntryId, Instant availableOn,
                                          Instant createdAt) {
        String id = Ids.generate("txn");
        jdbc.sql("""
                        INSERT INTO balance_transactions (id, merchant_id, livemode, currency, type, source_id,
                                                          parent_id, gross, fee, net, description,
                                                          journal_entry_id, available_on, created_at)
                        VALUES (:id, :merchant, :livemode, :currency, :type, :source, :parent, :gross, :fee, :net,
                                :description, :entry, :availableOn, :createdAt)
                        """)
                .param("id", id)
                .param("merchant", merchantId)
                .param("livemode", livemode)
                .param("currency", currency)
                .param("type", type)
                .param("source", sourceId)
                .param("parent", parentId, java.sql.Types.VARCHAR)
                .param("gross", gross)
                .param("fee", fee)
                .param("net", net)
                .param("description", description)
                .param("entry", journalEntryId)
                .param("availableOn", Sql.ts(availableOn), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", Sql.ts(createdAt))
                .update();
        BalanceTransactionEvent.emit(outbox, id, merchantId, livemode, currency, type, sourceId, parentId, gross, fee, net,
                description, createdAt);
    }

    /** Tells other services a journal entry exists; {@code amount} is its effect on the merchant's balance. */
    private void emitPosted(String entryId, String entryType, String sourceId, String merchantId, boolean livemode,
                            String currency, long amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entry_id", entryId);
        payload.put("entry_type", entryType);
        payload.put("source_id", sourceId);
        payload.put("currency", currency);
        payload.put("amount", amount);
        outbox.write(Topics.LEDGER_ENTRIES, "JournalEntryPosted", merchantId, merchantId, livemode, payload);
    }

    /**
     * Tells payouts that the merchant's payable balance changed by {@code amount} (negative for a refund
     * of already-available funds). Payouts adds up the entries that no payout has claimed yet.
     */
    private void emitFundsAvailable(String entryId, String sourceId, String merchantId, boolean livemode,
                                    String currency, long amount, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entry_id", entryId);
        payload.put("source_id", sourceId);
        payload.put("currency", currency);
        payload.put("amount", amount);
        payload.put("reason", reason);
        outbox.write(Topics.LEDGER_ENTRIES, "FundsAvailable", merchantId, merchantId, livemode, payload);
    }
}
