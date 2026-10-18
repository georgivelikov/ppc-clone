package dev.cutpay.billing.credit;

import static dev.cutpay.billing.config.Sql.instant;
import static dev.cutpay.billing.config.Sql.ts;

import dev.cutpay.web.Ids;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the credit a customer holds. Every change is a signed entry and moves the balance by the same amount. */
@Repository
public class CreditRepository {

    public record Entry(String id, String currency, long amount, long balanceAfter, String reason, String invoiceId,
                        Instant createdAt) {
    }

    public record Balance(String currency, long amount) {
    }

    private final JdbcClient jdbc;

    CreditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the balance row, creating it empty if the customer never had credit. Returns the balance. */
    public long lock(String merchantId, boolean livemode, String customerId, String currency, Instant now) {
        jdbc.sql("""
                        INSERT INTO customer_credit (customer_id, currency, merchant_id, livemode, balance, updated_at)
                        VALUES (:customer, :currency, :merchant, :livemode, 0, :now) ON CONFLICT DO NOTHING
                        """)
                .param("customer", customerId).param("currency", currency).param("merchant", merchantId)
                .param("livemode", livemode).param("now", ts(now)).update();
        return jdbc.sql("""
                        SELECT balance FROM customer_credit
                         WHERE merchant_id = :merchant AND livemode = :livemode AND customer_id = :customer
                           AND currency = :currency FOR UPDATE
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("customer", customerId)
                .param("currency", currency).query(Long.class).single();
    }

    /** Adds {@code amount} (negative to spend). The caller holds the lock and has checked the balance stays above zero. */
    public long add(String merchantId, boolean livemode, String customerId, String currency, long amount, String reason,
                    String invoiceId, Instant now) {
        long after = jdbc.sql("""
                        UPDATE customer_credit SET balance = balance + :amount, updated_at = :now
                         WHERE merchant_id = :merchant AND livemode = :livemode AND customer_id = :customer
                           AND currency = :currency RETURNING balance
                        """)
                .param("amount", amount).param("now", ts(now)).param("merchant", merchantId).param("livemode", livemode)
                .param("customer", customerId).param("currency", currency)
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO customer_credit_entries (id, customer_id, currency, merchant_id, livemode, amount,
                            balance_after, reason, invoice_id, created_at)
                        VALUES (:id, :customer, :currency, :merchant, :livemode, :amount, :after, :reason, :invoice, :now)
                        """)
                .param("id", Ids.generate("cce")).param("customer", customerId).param("currency", currency)
                .param("merchant", merchantId).param("livemode", livemode).param("amount", amount).param("after", after)
                .param("reason", reason).param("invoice", invoiceId).param("now", ts(now)).update();
        return after;
    }

    public List<Balance> balances(String merchantId, boolean livemode, String customerId) {
        return jdbc.sql("""
                        SELECT currency, balance FROM customer_credit
                         WHERE merchant_id = :merchant AND livemode = :livemode AND customer_id = :customer AND balance > 0
                         ORDER BY currency
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("customer", customerId)
                .query((rs, n) -> new Balance(rs.getString("currency").trim(), rs.getLong("balance"))).list();
    }

    public List<Entry> entries(String merchantId, boolean livemode, String customerId, int limit) {
        return jdbc.sql("""
                        SELECT id, currency, amount, balance_after, reason, invoice_id, created_at
                          FROM customer_credit_entries
                         WHERE merchant_id = :merchant AND livemode = :livemode AND customer_id = :customer
                         ORDER BY created_at DESC, id DESC LIMIT :limit
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("customer", customerId)
                .param("limit", limit)
                .query((rs, n) -> new Entry(rs.getString("id"), rs.getString("currency").trim(), rs.getLong("amount"),
                        rs.getLong("balance_after"), rs.getString("reason"), rs.getString("invoice_id"),
                        instant(rs, "created_at")))
                .list();
    }
}
