package dev.cutpay.ledger.api;

import dev.cutpay.ledger.api.BalanceDtos.AmountView;
import dev.cutpay.ledger.api.BalanceDtos.BalanceView;
import dev.cutpay.ledger.api.BalanceDtos.DayView;
import dev.cutpay.ledger.api.BalanceDtos.SummaryView;
import dev.cutpay.ledger.api.BalanceDtos.TransactionList;
import dev.cutpay.ledger.api.BalanceDtos.TransactionView;
import dev.cutpay.ledger.posting.Sql;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.RequestMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BalanceController {

    private static final int MAX_PAGE = 100;

    private final JdbcClient jdbc;
    private final Clock clock;

    BalanceController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Pending, available and reserved amounts per currency. The ledger stores what the platform owes a
     * merchant as negative numbers (credits), so they are negated here: a positive amount is money the
     * merchant can expect, a negative one is money they owe (e.g. a refund after a payout).
     */
    @GetMapping("/v1/balance")
    BalanceView balance(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        boolean livemode = RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode);
        record Row(String type, String currency, long amount) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT a.type, a.currency, COALESCE(b.amount, 0) AS amount
                          FROM accounts a LEFT JOIN balances b ON b.account_id = a.id
                         WHERE a.merchant_id = :merchant AND a.livemode = :livemode
                           AND a.type IN ('merchant_pending', 'merchant_available', 'merchant_reserve')
                         ORDER BY a.currency, a.type
                        """)
                .param("merchant", merchant(jwt))
                .param("livemode", livemode)
                .query((rs, n) -> new Row(rs.getString("type"), rs.getString("currency").trim(),
                        rs.getLong("amount")))
                .list();

        List<AmountView> pending = new ArrayList<>();
        List<AmountView> available = new ArrayList<>();
        List<AmountView> reserved = new ArrayList<>();
        for (Row row : rows) {
            var view = new AmountView(-row.amount(), row.currency());
            switch (row.type()) {
                case "merchant_pending" -> pending.add(view);
                case "merchant_available" -> available.add(view);
                default -> reserved.add(view);
            }
        }
        return new BalanceView("balance", livemode, pending, available, reserved);
    }

    @GetMapping("/v1/balance_transactions")
    TransactionList transactions(@AuthenticationPrincipal Jwt jwt,
                                 @RequestParam(required = false) String type,
                                 // A payment id also matches the refunds made against it.
                                 @RequestParam(required = false) String source,
                                 @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                                 @RequestParam(name = "starting_after", required = false) String startingAfter,
                                 @RequestParam(defaultValue = "20") int limit) {
        boolean livemode = RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode);
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalidRequest("invalid_limit", "limit must be between 1 and " + MAX_PAGE, "limit");
        }
        StringBuilder sql = new StringBuilder("""
                SELECT id, type, source_id, parent_id, currency, gross, fee, net, description, available_on,
                       created_at, livemode
                  FROM balance_transactions
                 WHERE merchant_id = :merchant AND livemode = :livemode
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("merchant", merchant(jwt));
        params.put("livemode", livemode);
        if (type != null && !type.isBlank()) {
            sql.append(" AND type = :type");
            params.put("type", type);
        }
        if (source != null && !source.isBlank()) {
            sql.append(" AND (source_id = :source OR parent_id = :source)");
            params.put("source", source);
        }
        if (startingAfter != null && !startingAfter.isBlank()) {
            sql.append(" AND id < :after");
            params.put("after", startingAfter);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", limit + 1);

        List<TransactionView> rows = new ArrayList<>(jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new TransactionView(rs.getString("id"), "balance_transaction",
                        rs.getString("type"), rs.getString("source_id"), rs.getString("parent_id"),
                        rs.getString("currency").trim(), rs.getLong("gross"), rs.getLong("fee"), rs.getLong("net"),
                        rs.getString("description"), Sql.instant(rs, "available_on"), Sql.instant(rs, "created_at"),
                        rs.getBoolean("livemode")))
                .list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows.remove(rows.size() - 1);
        }
        return new TransactionList("list", rows, hasMore);
    }

    /** Daily card volume, fees and net per currency for the Balances chart; days without payments are included. */
    @GetMapping("/v1/balance_summary")
    SummaryView summary(@AuthenticationPrincipal Jwt jwt,
                        @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                        @RequestParam(defaultValue = "14") int days) {
        boolean livemode = RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requestedLivemode);
        if (days < 1 || days > 90) {
            throw ApiException.invalidRequest("invalid_days", "days must be between 1 and 90", "days");
        }
        var today = clock.instant().atOffset(java.time.ZoneOffset.UTC).toLocalDate();
        var first = today.minusDays(days - 1L);
        record Day(String date, String currency, long volume, long fees, long net) {
        }
        List<Day> found = jdbc.sql("""
                        SELECT (created_at AT TIME ZONE 'UTC')::date AS day, currency,
                               COALESCE(SUM(gross), 0) AS volume, COALESCE(SUM(fee), 0) AS fees,
                               COALESCE(SUM(net), 0) AS net
                          FROM balance_transactions
                         WHERE merchant_id = :merchant AND livemode = :livemode AND type = 'payment'
                           AND created_at >= :since
                         GROUP BY 1, currency
                         ORDER BY 1, currency
                        """)
                .param("merchant", merchant(jwt))
                .param("livemode", livemode)
                .param("since", Sql.ts(first.atStartOfDay().toInstant(java.time.ZoneOffset.UTC)))
                .query((rs, n) -> new Day(rs.getString("day"), rs.getString("currency").trim(),
                        rs.getLong("volume"), rs.getLong("fees"), rs.getLong("net")))
                .list();

        Map<String, Day> byKey = new LinkedHashMap<>();
        List<String> currencies = new ArrayList<>();
        for (Day d : found) {
            byKey.put(d.date() + "|" + d.currency(), d);
            if (!currencies.contains(d.currency())) {
                currencies.add(d.currency());
            }
        }
        List<DayView> data = new ArrayList<>();
        for (String currency : currencies) {
            for (int i = 0; i < days; i++) {
                String date = first.plusDays(i).toString();
                Day d = byKey.get(date + "|" + currency);
                data.add(d == null ? new DayView(date, currency, 0, 0, 0)
                        : new DayView(date, currency, d.volume(), d.fees(), d.net()));
            }
        }
        return new SummaryView("list", data);
    }

    private static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }
}
