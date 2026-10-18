package dev.cutpay.billing.invoice;

import static dev.cutpay.billing.config.Sql.instant;
import static dev.cutpay.billing.config.Sql.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for invoices, their lines, the lines waiting for the next invoice, and the retries of unpaid ones. */
@Repository
public class InvoiceRepository {

    public static final String DRAFT = "draft";
    public static final String OPEN = "open";
    public static final String PAID = "paid";
    public static final String VOID = "void";
    public static final String UNCOLLECTIBLE = "uncollectible";

    private static final String COLUMNS = """
            id, merchant_id, merchant_name, livemode, subscription_id, customer_id, customer_email, customer_name, number,
            status, billing_reason, collection_method, currency, subtotal, tax, total, amount_paid, tax_rate_bps,
            description, default_payment_method, payment_intent_id, last_failed_payment_id, failure_code, hosted_token,
            attempt_count, first_failed_at, period_start, period_end, due_date, created_at, finalized_at, paid_at,
            voided_at, updated_at, credit_applied
            """;

    public record Line(String id, String description, long unitAmount, int quantity, long amount, String priceId,
                       boolean proration, Instant periodStart, Instant periodEnd) {
    }

    public record Invoice(String id, String merchantId, String merchantName, boolean livemode, String subscriptionId,
                          String customerId, String customerEmail, String customerName, String number, String status,
                          String billingReason, String collectionMethod, String currency, long subtotal, long tax,
                          long total, long amountPaid, int taxRateBps, String description, String defaultPaymentMethod,
                          String paymentIntentId, String lastFailedPaymentId, String failureCode, String hostedToken,
                          int attemptCount, Instant firstFailedAt, Instant periodStart, Instant periodEnd,
                          Instant dueDate, Instant createdAt, Instant finalizedAt, Instant paidAt, Instant voidedAt,
                          Instant updatedAt, long creditApplied, List<Line> lines) {
        public boolean open() {
            return OPEN.equals(status);
        }
    }

    /** A line waiting for the next invoice of a subscription. */
    public record PendingItem(String id, String description, long unitAmount, int quantity, long amount, String priceId,
                              boolean proration, Instant periodStart, Instant periodEnd) {
    }

    public record DunningAttempt(String id, String invoiceId, int attemptNo, Instant scheduledAt) {
    }

    private final JdbcClient jdbc;

    InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Invoice i) {
        jdbc.sql("""
                        INSERT INTO invoices (id, merchant_id, merchant_name, livemode, subscription_id, customer_id,
                            customer_email, customer_name, number, status, billing_reason, collection_method, currency,
                            subtotal, tax, total, amount_paid, tax_rate_bps, description, default_payment_method,
                            hosted_token, period_start, period_end, due_date, created_at, finalized_at, updated_at,
                            credit_applied)
                        VALUES (:id, :merchant, :merchantName, :livemode, :subscription, :customer, :email, :name,
                            :number, :status, :reason, :method, :currency, :subtotal, :tax, :total, 0, :taxRate,
                            :description, :pm, :token, :periodStart, :periodEnd, :due, :now, :finalized, :now, :credit)
                        """)
                .param("credit", i.creditApplied())
                .param("id", i.id()).param("merchant", i.merchantId()).param("merchantName", i.merchantName())
                .param("livemode", i.livemode()).param("subscription", i.subscriptionId(), Types.VARCHAR)
                .param("customer", i.customerId()).param("email", i.customerEmail()).param("name", i.customerName())
                .param("number", i.number(), Types.VARCHAR).param("status", i.status()).param("reason", i.billingReason())
                .param("method", i.collectionMethod()).param("currency", i.currency()).param("subtotal", i.subtotal())
                .param("tax", i.tax()).param("total", i.total()).param("taxRate", i.taxRateBps())
                .param("description", i.description()).param("pm", i.defaultPaymentMethod(), Types.VARCHAR)
                .param("token", i.hostedToken()).param("periodStart", ts(i.periodStart()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("periodEnd", ts(i.periodEnd()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("due", ts(i.dueDate()), Types.TIMESTAMP_WITH_TIMEZONE).param("now", ts(i.createdAt()))
                .param("finalized", ts(i.finalizedAt()), Types.TIMESTAMP_WITH_TIMEZONE).update();
        insertLines(i.id(), i.lines());
    }

    public void insertLines(String invoiceId, List<Line> lines) {
        int position = 0;
        for (Line line : lines) {
            jdbc.sql("""
                            INSERT INTO invoice_lines (id, invoice_id, description, unit_amount, quantity, amount, price_id,
                                                       proration, period_start, period_end, position)
                            VALUES (:id, :invoice, :description, :unit, :quantity, :amount, :price, :proration, :start,
                                    :end, :position)
                            """)
                    .param("id", line.id()).param("invoice", invoiceId).param("description", line.description())
                    .param("unit", line.unitAmount()).param("quantity", line.quantity()).param("amount", line.amount())
                    .param("price", line.priceId(), Types.VARCHAR).param("proration", line.proration())
                    .param("start", ts(line.periodStart()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("end", ts(line.periodEnd()), Types.TIMESTAMP_WITH_TIMEZONE).param("position", position++)
                    .update();
        }
    }

    public Optional<Invoice> find(String id) {
        return one("SELECT " + COLUMNS + " FROM invoices WHERE id = :v", id);
    }

    public Optional<Invoice> lock(String id) {
        return one("SELECT " + COLUMNS + " FROM invoices WHERE id = :v FOR UPDATE", id);
    }

    public Optional<Invoice> findByToken(String token) {
        return one("SELECT " + COLUMNS + " FROM invoices WHERE hosted_token = :v", token);
    }

    public Optional<Invoice> findByPaymentIntent(String paymentIntentId) {
        return one("SELECT " + COLUMNS + " FROM invoices WHERE payment_intent_id = :v", paymentIntentId);
    }

    private Optional<Invoice> one(String sql, String value) {
        return jdbc.sql(sql).param("v", value).query(this::map).optional().map(this::withLines);
    }

    public List<Invoice> page(String merchantId, boolean livemode, String status, String customerId,
                              String subscriptionId, String startingAfter, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM invoices
                        WHERE merchant_id = :merchant AND livemode = :livemode
                          AND (CAST(:status AS text) IS NULL OR status = :status)
                          AND (CAST(:customer AS text) IS NULL OR customer_id = :customer)
                          AND (CAST(:subscription AS text) IS NULL OR subscription_id = :subscription)
                          AND (CAST(:after AS text) IS NULL OR id < :after)
                        ORDER BY id DESC LIMIT :limit
                        """)
                .param("merchant", merchantId).param("livemode", livemode).param("status", status, Types.VARCHAR)
                .param("customer", customerId, Types.VARCHAR).param("subscription", subscriptionId, Types.VARCHAR)
                .param("after", startingAfter, Types.VARCHAR).param("limit", limit)
                .query(this::map).list().stream().map(this::withLines).toList();
    }

    /** The next invoice number of the merchant in this mode: INV-0001, INV-0002, … */
    public String nextNumber(String merchantId, boolean livemode) {
        long number = jdbc.sql("""
                        INSERT INTO invoice_counters (merchant_id, livemode, next_number) VALUES (:merchant, :livemode, 2)
                        ON CONFLICT (merchant_id, livemode) DO UPDATE SET next_number = invoice_counters.next_number + 1
                        RETURNING next_number - 1
                        """)
                .param("merchant", merchantId).param("livemode", livemode).query(Long.class).single();
        return "INV-%04d".formatted(number);
    }

    public void finalizeDraft(String id, String number, Instant dueDate, Instant now) {
        jdbc.sql("""
                        UPDATE invoices SET status = 'open', number = :number, due_date = :due, finalized_at = :now,
                               updated_at = :now WHERE id = :id
                        """)
                .param("id", id).param("number", number).param("due", ts(dueDate), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", ts(now)).update();
    }

    /** Takes credit off a draft that is being finalized: {@code total} is what is left to pay by card. */
    public void applyCredit(String id, long creditApplied, long total) {
        jdbc.sql("UPDATE invoices SET credit_applied = :credit, total = :total WHERE id = :id")
                .param("id", id).param("credit", creditApplied).param("total", total).update();
    }

    public void setStatus(String id, String status, Instant now) {
        jdbc.sql("""
                        UPDATE invoices SET status = :status, updated_at = :now,
                               voided_at = CASE WHEN :status = 'void' THEN :now ELSE voided_at END
                         WHERE id = :id
                        """)
                .param("id", id).param("status", status).param("now", ts(now)).update();
    }

    public void markPaid(String id, long amountPaid, String paymentIntentId, Instant now) {
        jdbc.sql("""
                        UPDATE invoices SET status = 'paid', amount_paid = :amount, paid_at = :now, failure_code = NULL,
                               payment_intent_id = COALESCE(:payment, payment_intent_id), updated_at = :now
                         WHERE id = :id
                        """)
                .param("id", id).param("amount", amountPaid).param("payment", paymentIntentId, Types.VARCHAR)
                .param("now", ts(now)).update();
    }

    /** Remembers an attempt before the card is charged, so a payment event can find its invoice. */
    public void startAttempt(String id, String paymentIntentId, Instant now) {
        jdbc.sql("""
                        UPDATE invoices SET payment_intent_id = :payment, attempt_count = attempt_count + 1,
                               updated_at = :now WHERE id = :id
                        """)
                .param("id", id).param("payment", paymentIntentId, Types.VARCHAR).param("now", ts(now)).update();
    }

    public void recordFailure(String id, String paymentIntentId, String code, Instant now) {
        jdbc.sql("""
                        UPDATE invoices SET last_failed_payment_id = :payment, failure_code = :code,
                               first_failed_at = COALESCE(first_failed_at, :now), updated_at = :now WHERE id = :id
                        """)
                .param("id", id).param("payment", paymentIntentId, Types.VARCHAR).param("code", code)
                .param("now", ts(now)).update();
    }

    public void countAttempt(String id, Instant now) {
        jdbc.sql("UPDATE invoices SET attempt_count = attempt_count + 1, updated_at = :now WHERE id = :id")
                .param("id", id).param("now", ts(now)).update();
    }

    public void deleteDraft(String id) {
        jdbc.sql("DELETE FROM invoice_lines WHERE invoice_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM invoices WHERE id = :id AND status = 'draft'").param("id", id).update();
    }

    // --- lines waiting for the next invoice --------------------------------------------------------

    public void addPending(String merchantId, boolean livemode, String subscriptionId, PendingItem item, Instant now) {
        jdbc.sql("""
                        INSERT INTO pending_invoice_items (id, merchant_id, livemode, subscription_id, description,
                            unit_amount, quantity, amount, price_id, proration, period_start, period_end, created_at)
                        VALUES (:id, :merchant, :livemode, :subscription, :description, :unit, :quantity, :amount,
                            :price, :proration, :start, :end, :now)
                        """)
                .param("id", item.id()).param("merchant", merchantId).param("livemode", livemode)
                .param("subscription", subscriptionId).param("description", item.description())
                .param("unit", item.unitAmount()).param("quantity", item.quantity()).param("amount", item.amount())
                .param("price", item.priceId(), Types.VARCHAR).param("proration", item.proration())
                .param("start", ts(item.periodStart()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("end", ts(item.periodEnd()), Types.TIMESTAMP_WITH_TIMEZONE).param("now", ts(now)).update();
    }

    public List<PendingItem> pending(String subscriptionId) {
        return jdbc.sql("""
                        SELECT id, description, unit_amount, quantity, amount, price_id, proration, period_start, period_end
                          FROM pending_invoice_items WHERE subscription_id = :id AND invoice_id IS NULL ORDER BY created_at, id
                        """)
                .param("id", subscriptionId)
                .query((rs, n) -> new PendingItem(rs.getString("id"), rs.getString("description"),
                        rs.getLong("unit_amount"), rs.getInt("quantity"), rs.getLong("amount"), rs.getString("price_id"),
                        rs.getBoolean("proration"), instant(rs, "period_start"), instant(rs, "period_end")))
                .list();
    }

    public void claimPending(String subscriptionId, String invoiceId) {
        jdbc.sql("UPDATE pending_invoice_items SET invoice_id = :invoice WHERE subscription_id = :id AND invoice_id IS NULL")
                .param("id", subscriptionId).param("invoice", invoiceId).update();
    }

    // --- retries of unpaid invoices ----------------------------------------------------------------

    public void scheduleAttempt(String id, String invoiceId, int attemptNo, Instant at) {
        jdbc.sql("""
                        INSERT INTO dunning_attempts (id, invoice_id, attempt_no, scheduled_at)
                        VALUES (:id, :invoice, :no, :at) ON CONFLICT (invoice_id, attempt_no) DO NOTHING
                        """)
                .param("id", id).param("invoice", invoiceId).param("no", attemptNo).param("at", ts(at)).update();
    }

    public List<DunningAttempt> dueAttempts(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT id, invoice_id, attempt_no, scheduled_at FROM dunning_attempts
                         WHERE executed_at IS NULL AND scheduled_at <= :now ORDER BY scheduled_at, id LIMIT :limit
                        """)
                .param("now", ts(now)).param("limit", limit)
                .query((rs, n) -> new DunningAttempt(rs.getString("id"), rs.getString("invoice_id"),
                        rs.getInt("attempt_no"), instant(rs, "scheduled_at")))
                .list();
    }

    /** Claims an attempt; false if another worker did. */
    public boolean completeAttempt(String id, String result, Instant now) {
        return jdbc.sql("UPDATE dunning_attempts SET executed_at = :now, result = :result WHERE id = :id AND executed_at IS NULL")
                .param("id", id).param("result", result).param("now", ts(now)).update() == 1;
    }

    public void setAttemptResult(String id, String result) {
        jdbc.sql("UPDATE dunning_attempts SET result = :result WHERE id = :id").param("id", id).param("result", result).update();
    }

    public int cancelPendingAttempts(String invoiceId, Instant now) {
        return jdbc.sql("UPDATE dunning_attempts SET executed_at = :now, result = 'canceled' WHERE invoice_id = :id AND executed_at IS NULL")
                .param("id", invoiceId).param("now", ts(now)).update();
    }

    public record AttemptRow(int attemptNo, Instant scheduledAt, Instant executedAt, String result) {
    }

    public List<AttemptRow> attempts(String invoiceId) {
        return jdbc.sql("SELECT attempt_no, scheduled_at, executed_at, result FROM dunning_attempts WHERE invoice_id = :id ORDER BY attempt_no")
                .param("id", invoiceId)
                .query((rs, n) -> new AttemptRow(rs.getInt("attempt_no"), instant(rs, "scheduled_at"),
                        instant(rs, "executed_at"), rs.getString("result")))
                .list();
    }

    // --- mapping -----------------------------------------------------------------------------------

    private Invoice withLines(Invoice i) {
        List<Line> lines = jdbc.sql("""
                        SELECT id, description, unit_amount, quantity, amount, price_id, proration, period_start, period_end
                          FROM invoice_lines WHERE invoice_id = :id ORDER BY position
                        """)
                .param("id", i.id())
                .query((rs, n) -> new Line(rs.getString("id"), rs.getString("description"), rs.getLong("unit_amount"),
                        rs.getInt("quantity"), rs.getLong("amount"), rs.getString("price_id"), rs.getBoolean("proration"),
                        instant(rs, "period_start"), instant(rs, "period_end")))
                .list();
        return new Invoice(i.id(), i.merchantId(), i.merchantName(), i.livemode(), i.subscriptionId(), i.customerId(),
                i.customerEmail(), i.customerName(), i.number(), i.status(), i.billingReason(), i.collectionMethod(),
                i.currency(), i.subtotal(), i.tax(), i.total(), i.amountPaid(), i.taxRateBps(), i.description(),
                i.defaultPaymentMethod(), i.paymentIntentId(), i.lastFailedPaymentId(), i.failureCode(), i.hostedToken(),
                i.attemptCount(), i.firstFailedAt(), i.periodStart(), i.periodEnd(), i.dueDate(), i.createdAt(),
                i.finalizedAt(), i.paidAt(), i.voidedAt(), i.updatedAt(), i.creditApplied(), lines);
    }

    private Invoice map(ResultSet rs, int row) throws SQLException {
        return new Invoice(rs.getString("id"), rs.getString("merchant_id"), rs.getString("merchant_name"),
                rs.getBoolean("livemode"), rs.getString("subscription_id"), rs.getString("customer_id"),
                rs.getString("customer_email"), rs.getString("customer_name"), rs.getString("number"),
                rs.getString("status"), rs.getString("billing_reason"), rs.getString("collection_method"),
                rs.getString("currency").trim(), rs.getLong("subtotal"), rs.getLong("tax"), rs.getLong("total"),
                rs.getLong("amount_paid"), rs.getInt("tax_rate_bps"), rs.getString("description"),
                rs.getString("default_payment_method"), rs.getString("payment_intent_id"),
                rs.getString("last_failed_payment_id"), rs.getString("failure_code"), rs.getString("hosted_token"),
                rs.getInt("attempt_count"), instant(rs, "first_failed_at"), instant(rs, "period_start"),
                instant(rs, "period_end"), instant(rs, "due_date"), instant(rs, "created_at"),
                instant(rs, "finalized_at"), instant(rs, "paid_at"), instant(rs, "voided_at"),
                instant(rs, "updated_at"), rs.getLong("credit_applied"), List.of());
    }
}
