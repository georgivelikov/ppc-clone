package dev.cutpay.payouts.payout;

import dev.cutpay.money.SupportedCurrencies;
import dev.cutpay.payouts.payout.PayoutDtos.CreatePayoutRequest;
import dev.cutpay.payouts.payout.PayoutDtos.ItemView;
import dev.cutpay.payouts.payout.PayoutDtos.PayoutDetail;
import dev.cutpay.payouts.payout.PayoutDtos.PayoutList;
import dev.cutpay.payouts.payout.PayoutDtos.PayoutView;
import dev.cutpay.payouts.payout.PayoutDtos.Reconciliation;
import dev.cutpay.payouts.payout.PayoutDtos.ScheduleView;
import dev.cutpay.payouts.payout.PayoutDtos.UpdateScheduleRequest;
import dev.cutpay.payouts.payout.PayoutService.Group;
import dev.cutpay.payouts.payout.PayoutService.ScheduleSettings;
import dev.cutpay.web.ApiException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PayoutController {

    private static final int MAX_PAGE = 100;
    private static final Set<String> SCHEDULES = Set.of("daily", "weekly", "manual");

    private final JdbcClient jdbc;
    private final PayoutService payouts;

    PayoutController(JdbcClient jdbc, PayoutService payouts) {
        this.jdbc = jdbc;
        this.payouts = payouts;
    }

    @GetMapping("/v1/payouts")
    PayoutList list(@AuthenticationPrincipal Jwt jwt,
                    @RequestParam(required = false) String status,
                    @RequestParam(defaultValue = "false") boolean livemode,
                    @RequestParam(name = "starting_after", required = false) String startingAfter,
                    @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalidRequest("invalid_limit", "limit must be between 1 and " + MAX_PAGE, "limit");
        }
        StringBuilder sql = new StringBuilder(SELECT + " WHERE merchant_id = :merchant AND livemode = :livemode");
        Map<String, Object> params = new HashMap<>();
        params.put("merchant", merchant(jwt));
        params.put("livemode", livemode);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        if (startingAfter != null && !startingAfter.isBlank()) {
            sql.append(" AND id < :after");
            params.put("after", startingAfter);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", limit + 1);
        List<PayoutView> rows = new ArrayList<>(
                jdbc.sql(sql.toString()).params(params).query((rs, n) -> map(rs)).list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows.remove(rows.size() - 1);
        }
        return new PayoutList("list", rows, hasMore);
    }

    /** What "Pay out now" would do: the payable amount, the fee, and what would reach the bank. */
    @GetMapping("/v1/payouts/preview")
    PayoutService.Preview preview(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(defaultValue = "false") boolean livemode,
                                  @RequestParam(defaultValue = "EUR") String currency) {
        return payouts.preview(new Group(merchant(jwt), livemode, currency(currency)));
    }

    @GetMapping("/v1/payouts/{id}")
    PayoutDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        PayoutView payout = find(jwt, id);
        List<ItemView> items = jdbc.sql("""
                        SELECT journal_entry_ref, source_id, amount FROM payout_items
                         WHERE payout_id = :id ORDER BY journal_entry_ref
                        """)
                .param("id", id)
                .query((rs, n) -> new ItemView(rs.getString("journal_entry_ref"), rs.getString("source_id"),
                        rs.getLong("amount")))
                .list();
        return new PayoutDetail(payout, items);
    }

    /** Checks that the transactions inside a payout add up to the money it moved. */
    @GetMapping("/v1/payouts/{id}/reconciliation")
    Reconciliation reconciliation(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        PayoutView payout = find(jwt, id);
        record Totals(long total, int count) {
        }
        Totals totals = jdbc.sql("SELECT COALESCE(SUM(amount), 0) AS total, COUNT(*) AS n FROM payout_items "
                        + "WHERE payout_id = :id")
                .param("id", id)
                .query((rs, n) -> new Totals(rs.getLong("total"), rs.getInt("n")))
                .single();
        return new Reconciliation(id, payout.amount(), payout.fee(), totals.total(), totals.count(),
                totals.total() == payout.amount() + payout.fee());
    }

    @PostMapping("/v1/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    PayoutView create(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean livemode,
                      @RequestBody(required = false) CreatePayoutRequest request) {
        String currency = currency(request == null ? null : request.currency());
        PayoutService.Created created = payouts.createInstantPayout(new Group(merchant(jwt), livemode, currency));
        return find(jwt, created.payoutId());
    }

    @GetMapping("/v1/payout_schedule")
    ScheduleView schedule(@AuthenticationPrincipal Jwt jwt) {
        return view(payouts.schedule(merchant(jwt)));
    }

    @PutMapping("/v1/payout_schedule")
    ScheduleView updateSchedule(@AuthenticationPrincipal Jwt jwt, @RequestBody UpdateScheduleRequest request) {
        ScheduleSettings current = payouts.schedule(merchant(jwt));
        String schedule = request.schedule() == null ? current.schedule() : request.schedule();
        if (!SCHEDULES.contains(schedule)) {
            throw ApiException.invalidRequest("invalid_schedule", "schedule must be daily, weekly or manual",
                    "schedule");
        }
        int delay = request.delayDays() == null ? current.delayDays() : request.delayDays();
        if (delay < 0 || delay > 30) {
            throw ApiException.invalidRequest("invalid_delay", "delay_days must be between 0 and 30", "delay_days");
        }
        String anchor = request.weeklyAnchor() == null ? current.weeklyAnchor()
                : parseDay(request.weeklyAnchor()).name();
        return view(payouts.updateSchedule(merchant(jwt), new ScheduleSettings(schedule, delay, anchor)));
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static final String SELECT = """
            SELECT id, amount, fee, currency, status, kind, arrival_date, bank_account_id, failure_code,
                   created_at, livemode
              FROM payouts
            """;

    private PayoutView find(Jwt jwt, String id) {
        return jdbc.sql(SELECT + " WHERE id = :id AND merchant_id = :merchant")
                .param("id", id)
                .param("merchant", merchant(jwt))
                .query((rs, n) -> map(rs))
                .optional()
                .orElseThrow(() -> ApiException.notFound("payout_not_found", "No such payout"));
    }

    private static PayoutView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PayoutView(rs.getString("id"), "payout", rs.getLong("amount"), rs.getLong("fee"),
                rs.getString("currency").trim(), rs.getString("status"), rs.getString("kind"),
                rs.getObject("arrival_date", LocalDate.class), rs.getString("bank_account_id"),
                rs.getString("failure_code"), Sql.instant(rs, "created_at"), rs.getBoolean("livemode"));
    }

    private static ScheduleView view(ScheduleSettings s) {
        return new ScheduleView("payout_schedule", s.schedule(), s.delayDays(), s.weeklyAnchor());
    }

    private static DayOfWeek parseDay(String value) {
        try {
            return DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("invalid_weekly_anchor", "weekly_anchor must be a day of the week",
                    "weekly_anchor");
        }
    }

    private static String currency(String value) {
        String currency = value == null || value.isBlank() ? "EUR" : value.toUpperCase(Locale.ROOT);
        if (!SupportedCurrencies.isSupported(currency)) {
            throw ApiException.invalidRequest("unsupported_currency", "Unsupported currency " + currency, "currency");
        }
        return currency;
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }
}
