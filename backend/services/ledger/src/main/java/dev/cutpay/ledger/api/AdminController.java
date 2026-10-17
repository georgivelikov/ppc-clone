package dev.cutpay.ledger.api;

import dev.cutpay.ledger.api.BalanceDtos.CurrencyTotal;
import dev.cutpay.ledger.api.BalanceDtos.TrialBalanceView;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator checks. Not routed by the gateway and not authenticated: reachable only on the service's own port. */
@RestController
class AdminController {

    private final JdbcClient jdbc;

    AdminController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The trial balance: every posting ever made, summed. In double-entry bookkeeping it is always zero
     * (per currency), because each entry debits exactly what it credits. Also reports "drift", the number
     * of accounts whose materialised balance disagrees with the sum of their postings.
     */
    @GetMapping("/admin/trial-balance")
    TrialBalanceView trialBalance() {
        List<CurrencyTotal> byCurrency = jdbc.sql("""
                        SELECT currency, COALESCE(SUM(amount), 0) AS total
                          FROM postings GROUP BY currency ORDER BY currency
                        """)
                .query((rs, n) -> new CurrencyTotal(rs.getString("currency").trim(), rs.getLong("total")))
                .list();
        long total = byCurrency.stream().mapToLong(CurrencyTotal::total).sum();
        long entries = jdbc.sql("SELECT COUNT(*) FROM journal_entries").query(Long.class).single();
        long postings = jdbc.sql("SELECT COUNT(*) FROM postings").query(Long.class).single();
        long drift = jdbc.sql("""
                        SELECT COUNT(*) FROM accounts a
                          LEFT JOIN balances b ON b.account_id = a.id
                         WHERE COALESCE(b.amount, 0) <>
                               (SELECT COALESCE(SUM(p.amount), 0) FROM postings p WHERE p.account_id = a.id)
                        """)
                .query(Long.class).single();
        boolean balanced = total == 0 && byCurrency.stream().allMatch(c -> c.total() == 0);
        return new TrialBalanceView(balanced, total, entries, postings, drift, byCurrency);
    }
}
