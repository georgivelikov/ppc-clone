package dev.cutpay.payouts.payout;

import dev.cutpay.crypto.FieldCipher;
import dev.cutpay.events.InboxGuard;
import dev.cutpay.events.OutboxWriter;
import dev.cutpay.payouts.config.PayoutProperties;
import dev.cutpay.payouts.config.Topics;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates payouts from the funds the ledger has made available, and settles them with the fake bank.
 *
 * <p>The payable balance is a projection of the ledger's {@code FundsAvailable} events
 * ({@code available_entries}). A payout claims all unclaimed rows of one merchant, mode and currency in a
 * single transaction, so no ledger entry can end up in two payouts. Negative rows (a refund of funds that
 * already became available) are claimed too, which deducts them from the payout.
 */
@Service
public class PayoutService {

    /** Test IBANs ending like this make the fake bank reject the transfer. */
    static final String CLOSED_ACCOUNT_SUFFIX = "0000";

    public record Group(String merchantId, boolean livemode, String currency) {
    }

    public record Created(String payoutId, long amount, long fee, String currency) {
    }

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final InboxGuard inbox;
    private final Clock clock;
    private final PayoutProperties properties;
    private final FieldCipher cipher;

    public PayoutService(JdbcClient jdbc, OutboxWriter outbox, InboxGuard inbox, Clock clock,
                         PayoutProperties properties, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.inbox = inbox;
        this.clock = clock;
        this.properties = properties;
        this.cipher = cipher;
    }

    // --- projections of other services' events ----------------------------------------------------

    @Transactional
    public void onFundsAvailable(String eventId, String merchantId, boolean livemode, String currency, String entryId,
                                 String sourceId, long amount) {
        if (!inbox.firstTime(eventId)) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO available_entries (entry_id, merchant_id, livemode, currency, amount, source_id)
                        VALUES (:entry, :merchant, :livemode, :currency, :amount, :source)
                        ON CONFLICT (entry_id) DO NOTHING
                        """)
                .param("entry", entryId)
                .param("merchant", merchantId)
                .param("livemode", livemode)
                .param("currency", currency)
                .param("amount", amount)
                .param("source", sourceId)
                .update();
    }

    @Transactional
    public void onMerchantCreated(String eventId, String merchantId) {
        if (!inbox.firstTime(eventId)) {
            return;
        }
        ensureSchedule(merchantId);
    }

    /**
     * A connected account is paid out like a merchant: it gets a bank account from the IBAN its platform gave and a
     * daily schedule. When the IBAN changes, the new account replaces the old one for future payouts.
     */
    @Transactional
    public void onConnectedAccount(String eventId, String accountId, boolean livemode, String iban, String holder,
                                   String currency, boolean replace) {
        if (!inbox.firstTime(eventId)) {
            return;
        }
        ensureSchedule(accountId);
        if (replace) {
            jdbc.sql("UPDATE bank_accounts SET status = 'removed' WHERE merchant_id = :account AND status = 'active'")
                    .param("account", accountId).update();
        }
        jdbc.sql("""
                        INSERT INTO bank_accounts (id, merchant_id, livemode, iban, iban_country, iban_last4, holder_name,
                                                   currency, status, created_at)
                        VALUES (:id, :account, :livemode, :iban, :country, :last4, :holder, :currency, 'active', :now)
                        """)
                .param("id", Ids.generate("ba"))
                .param("account", accountId)
                .param("livemode", livemode)
                .param("iban", cipher.encrypt(iban, accountId))
                .param("country", iban.substring(0, 2))
                .param("last4", iban.substring(iban.length() - 4))
                .param("holder", holder)
                .param("currency", currency)
                .param("now", Sql.ts(clock.instant()))
                .update();
    }

    /** New merchants start with a daily payout, two days after the sale. */
    public void ensureSchedule(String merchantId) {
        jdbc.sql("""
                        INSERT INTO payout_schedules (merchant_id, schedule, delay_days, weekly_anchor, updated_at)
                        VALUES (:merchant, 'daily', 2, 'MONDAY', :now)
                        ON CONFLICT (merchant_id) DO NOTHING
                        """)
                .param("merchant", merchantId)
                .param("now", Sql.ts(clock.instant()))
                .update();
    }

    // --- scheduled payouts ------------------------------------------------------------------------

    /** Merchant/mode/currency combinations that have a positive payable balance right now. */
    public List<Group> groupsWithPayableFunds() {
        return jdbc.sql("""
                        SELECT merchant_id, livemode, currency
                          FROM available_entries
                         WHERE payout_id IS NULL
                         GROUP BY merchant_id, livemode, currency
                        HAVING SUM(amount) > 0
                        """)
                .query((rs, n) -> new Group(rs.getString("merchant_id"), rs.getBoolean("livemode"),
                        rs.getString("currency").trim()))
                .list();
    }

    /**
     * Creates the scheduled payout for one group if the merchant's schedule says it is due today.
     * Runs in its own transaction, so one merchant's problem cannot hold up the others.
     */
    @Transactional
    public Optional<Created> createScheduledPayout(Group group) {
        if (isFrozen(group.merchantId())) {
            return Optional.empty(); // held by the back-office: the money waits
        }
        ensureSchedule(group.merchantId());
        record Schedule(String schedule, String anchor) {
        }
        Schedule schedule = jdbc.sql("SELECT schedule, weekly_anchor FROM payout_schedules WHERE merchant_id = :m")
                .param("m", group.merchantId())
                .query((rs, n) -> new Schedule(rs.getString("schedule"), rs.getString("weekly_anchor")))
                .single();
        LocalDate today = today();
        boolean due = switch (schedule.schedule()) {
            case "daily" -> true;
            case "weekly" -> today.getDayOfWeek() == DayOfWeek.valueOf(schedule.anchor());
            default -> false; // manual: the merchant pays out themselves
        };
        if (!due) {
            return Optional.empty();
        }
        Optional<String> bankAccount = bankAccountFor(group);
        if (bankAccount.isEmpty()) {
            return Optional.empty(); // the money simply waits until a bank account exists
        }
        // At most one scheduled payout per merchant, mode and currency per day.
        boolean alreadyRan = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM payout_runs
                                        WHERE merchant_id = :merchant AND livemode = :livemode
                                          AND currency = :currency AND run_date = :day)
                        """)
                .param("merchant", group.merchantId())
                .param("livemode", group.livemode())
                .param("currency", group.currency())
                .param("day", today)
                .query(Boolean.class)
                .single();
        if (alreadyRan) {
            return Optional.empty();
        }
        Optional<Created> created = createPayout(group, bankAccount.get(), "scheduled", today.plusDays(1));
        if (created.isPresent()) {
            jdbc.sql("""
                            INSERT INTO payout_runs (merchant_id, livemode, currency, run_date)
                            VALUES (:merchant, :livemode, :currency, :day)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("merchant", group.merchantId())
                    .param("livemode", group.livemode())
                    .param("currency", group.currency())
                    .param("day", today)
                    .update();
        }
        return created;
    }

    public boolean isFrozen(String merchantId) {
        return jdbc.sql("SELECT count(*) FROM payout_freezes WHERE merchant_id = :m").param("m", merchantId).query(Integer.class).single() > 0;
    }

    /** The back-office put payouts on hold, or lifted the hold. Safe to receive twice. */
    @Transactional
    public void onFreezeChanged(String eventId, String merchantId, boolean frozen) {
        if (!inbox.firstTime(eventId)) {
            return;
        }
        if (frozen) {
            jdbc.sql("INSERT INTO payout_freezes (merchant_id) VALUES (:m) ON CONFLICT DO NOTHING").param("m", merchantId).update();
        } else {
            jdbc.sql("DELETE FROM payout_freezes WHERE merchant_id = :m").param("m", merchantId).update();
        }
    }

    // --- instant payouts --------------------------------------------------------------------------

    /** What "Pay out now" would do right now: the payable amount, the fee and what reaches the bank. */
    public record Preview(String currency, long available, long fee, long net, boolean hasBankAccount) {
    }

    public Preview preview(Group group) {
        long available = payable(group);
        long fee = instantFee(Math.max(available, 0));
        return new Preview(group.currency(), available, fee, Math.max(available - fee, 0),
                bankAccountFor(group).isPresent());
    }

    /** A manual payout of everything payable, for a fee. */
    @Transactional
    public Created createInstantPayout(Group group) {
        if (isFrozen(group.merchantId())) {
            throw ApiException.forbidden("payouts_frozen", "Payouts are on hold for this business. Contact support.");
        }
        String bankAccount = bankAccountFor(group).orElseThrow(() -> ApiException.invalidRequest(
                "no_bank_account", "Add a bank account in the payout settings first.", null));
        return createPayout(group, bankAccount, "instant", today())
                .orElseThrow(() -> ApiException.invalidRequest("insufficient_available_balance",
                        "There is nothing available to pay out yet.", null));
    }

    // --- shared -----------------------------------------------------------------------------------

    /**
     * Claims every unclaimed entry of the group and creates the payout for their total, minus the fee.
     * The entries are locked first, so a concurrent payout for the same group waits and then finds nothing.
     */
    private Optional<Created> createPayout(Group group, String bankAccountId, String kind, LocalDate arrival) {
        record Entry(String entryId, String sourceId, long amount) {
        }
        List<Entry> entries = jdbc.sql("""
                        SELECT entry_id, source_id, amount FROM available_entries
                         WHERE payout_id IS NULL AND merchant_id = :merchant AND livemode = :livemode
                           AND currency = :currency
                         ORDER BY created_at, entry_id
                           FOR UPDATE
                        """)
                .param("merchant", group.merchantId())
                .param("livemode", group.livemode())
                .param("currency", group.currency())
                .query((rs, n) -> new Entry(rs.getString("entry_id"), rs.getString("source_id"), rs.getLong("amount")))
                .list();
        long gross = entries.stream().mapToLong(Entry::amount).sum();
        // Only a manual payout pays a fee; scheduled payouts are free.
        long fee = "instant".equals(kind) ? instantFee(gross) : 0;
        long net = gross - fee;
        if (gross <= 0 || net <= 0) {
            return Optional.empty();
        }

        String payoutId = Ids.generate("po");
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO payouts (id, merchant_id, livemode, amount, fee, currency, status, kind,
                                             arrival_date, bank_account_id, created_at, updated_at)
                        VALUES (:id, :merchant, :livemode, :amount, :fee, :currency, 'in_transit', :kind,
                                :arrival, :bank, :now, :now)
                        """)
                .param("id", payoutId)
                .param("merchant", group.merchantId())
                .param("livemode", group.livemode())
                .param("amount", net)
                .param("fee", fee)
                .param("currency", group.currency())
                .param("kind", kind)
                .param("arrival", arrival)
                .param("bank", bankAccountId)
                .param("now", Sql.ts(now))
                .update();
        for (Entry entry : entries) {
            jdbc.sql("UPDATE available_entries SET payout_id = :payout WHERE entry_id = :entry")
                    .param("payout", payoutId)
                    .param("entry", entry.entryId())
                    .update();
            jdbc.sql("""
                            INSERT INTO payout_items (payout_id, journal_entry_ref, source_id, amount)
                            VALUES (:payout, :entry, :source, :amount)
                            """)
                    .param("payout", payoutId)
                    .param("entry", entry.entryId())
                    .param("source", entry.sourceId())
                    .param("amount", entry.amount())
                    .update();
        }
        emit("PayoutCreated", payoutId, group.merchantId(), group.livemode(),
                payload(payoutId, net, fee, group.currency(), arrival, kind, "in_transit", null));
        return Optional.of(new Created(payoutId, net, fee, group.currency()));
    }

    private Optional<String> bankAccountFor(Group group) {
        return jdbc.sql("""
                        SELECT id FROM bank_accounts
                         WHERE merchant_id = :merchant AND livemode = :livemode AND currency = :currency
                           AND status = 'active'
                         ORDER BY id DESC LIMIT 1
                        """)
                .param("merchant", group.merchantId())
                .param("livemode", group.livemode())
                .param("currency", group.currency())
                .query(String.class)
                .optional();
    }

    private long payable(Group group) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) FROM available_entries
                         WHERE payout_id IS NULL AND merchant_id = :merchant AND livemode = :livemode
                           AND currency = :currency
                        """)
                .param("merchant", group.merchantId())
                .param("livemode", group.livemode())
                .param("currency", group.currency())
                .query(Long.class)
                .single();
    }

    /** The instant-payout fee, rounded once with banker's rounding like every other fee. */
    long instantFee(long amount) {
        return BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(properties.instantFeeBps()))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_EVEN)
                .longValueExact();
    }

    // --- the fake bank ----------------------------------------------------------------------------

    /**
     * Settles transfers the fake bank has had long enough: an IBAN ending in 0000 is a closed account and
     * the payout fails; everything else is paid. Time is measured on the simulated clock.
     *
     * @return how many payouts were settled
     */
    @Transactional
    public int settleDuePayouts(int limit) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.bankDelay());
        record Due(String id, String merchantId, boolean livemode, long amount, long fee, String currency,
                   String kind, LocalDate arrival, String iban) {
        }
        List<Due> due = jdbc.sql("""
                        SELECT p.id, p.merchant_id, p.livemode, p.amount, p.fee, p.currency, p.kind, p.arrival_date,
                               b.iban
                          FROM payouts p JOIN bank_accounts b ON b.id = p.bank_account_id
                         WHERE p.status = 'in_transit' AND p.created_at <= :cutoff
                         ORDER BY p.created_at
                         LIMIT :limit
                           FOR UPDATE OF p SKIP LOCKED
                        """)
                .param("cutoff", Sql.ts(cutoff))
                .param("limit", limit)
                .query((rs, n) -> new Due(rs.getString("id"), rs.getString("merchant_id"), rs.getBoolean("livemode"),
                        rs.getLong("amount"), rs.getLong("fee"), rs.getString("currency").trim(),
                        rs.getString("kind"), rs.getObject("arrival_date", LocalDate.class), rs.getString("iban")))
                .list();

        for (Due d : due) {
            boolean closed = cipher.decrypt(d.iban(), d.merchantId()).endsWith(CLOSED_ACCOUNT_SUFFIX);
            String status = closed ? "failed" : "paid";
            String failureCode = closed ? "account_closed" : null;
            jdbc.sql("""
                            UPDATE payouts SET status = :status, failure_code = :code, updated_at = :now
                             WHERE id = :id AND status = 'in_transit'
                            """)
                    .param("status", status)
                    .param("code", failureCode, Types.VARCHAR)
                    .param("now", Sql.ts(now))
                    .param("id", d.id())
                    .update();
            emit(closed ? "PayoutFailed" : "PayoutPaid", d.id(), d.merchantId(), d.livemode(),
                    payload(d.id(), d.amount(), d.fee(), d.currency(), d.arrival(), d.kind(), status, failureCode));
        }
        return due.size();
    }

    // --- events -----------------------------------------------------------------------------------

    public record ScheduleSettings(String schedule, int delayDays, String weeklyAnchor) {
    }

    public ScheduleSettings schedule(String merchantId) {
        ensureSchedule(merchantId);
        return jdbc.sql("SELECT schedule, delay_days, weekly_anchor FROM payout_schedules WHERE merchant_id = :m")
                .param("m", merchantId)
                .query((rs, n) -> new ScheduleSettings(rs.getString("schedule"), rs.getInt("delay_days"),
                        rs.getString("weekly_anchor")))
                .single();
    }

    /** Saves the schedule and tells the ledger, which owns how long funds stay pending. */
    @Transactional
    public ScheduleSettings updateSchedule(String merchantId, ScheduleSettings settings) {
        ensureSchedule(merchantId);
        jdbc.sql("""
                        UPDATE payout_schedules
                           SET schedule = :schedule, delay_days = :delay, weekly_anchor = :anchor, updated_at = :now
                         WHERE merchant_id = :merchant
                        """)
                .param("schedule", settings.schedule())
                .param("delay", settings.delayDays())
                .param("anchor", settings.weeklyAnchor())
                .param("now", Sql.ts(clock.instant()))
                .param("merchant", merchantId)
                .update();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchant_id", merchantId);
        payload.put("schedule", settings.schedule());
        payload.put("delay_days", settings.delayDays());
        outbox.write(Topics.PAYOUT, "PayoutScheduleChanged", merchantId, merchantId, null, payload);
        return settings;
    }

    private void emit(String type, String payoutId, String merchantId, boolean livemode, Map<String, Object> payload) {
        outbox.write(Topics.PAYOUT, type, payoutId, merchantId, livemode, payload);
    }

    private static Map<String, Object> payload(String payoutId, long amount, long fee, String currency,
                                               LocalDate arrival, String kind, String status, String failureCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payout_id", payoutId);
        payload.put("amount", amount);
        payload.put("fee", fee);
        payload.put("currency", currency);
        payload.put("arrival_date", arrival.toString());
        payload.put("kind", kind);
        payload.put("status", status);
        payload.put("failure_code", failureCode);
        return payload;
    }

    LocalDate today() {
        return clock.instant().atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
