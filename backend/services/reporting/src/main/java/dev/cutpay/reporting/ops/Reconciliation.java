package dev.cutpay.reporting.ops;

import dev.cutpay.web.Ids;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Compares three accounts of the same money: what the payments service says succeeded, what the ledger booked, and what
 * the acquirer's settlement file says it moved. Each is written by different code on a different path, so a bug in one
 * (a charge that never reached the ledger, a refund the bank never got) shows up as a difference here, usually long
 * before a merchant notices. See docs/adr/0032-resilience-and-reconciliation.md.
 *
 * <p>Events are asynchronous, so a difference on a fresh payment may just be in flight. A difference is only reported
 * when it is still there after waiting {@code cutpay.reconciliation.settle-wait} and looking again.
 */
@Component
public class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);
    private static final Set<String> SETTLED = Set.of("succeeded", "partially_refunded", "refunded");
    /** Statuses a payment is never in after the card was charged. "processing" is one of them: it is a step, not an outcome. */
    private static final Set<String> NEVER_CHARGED = Set.of("failed", "canceled", "processing", "requires_payment_method");

    public record Mismatch(String kind, String paymentId, String currency, Long paymentsAmount, Long ledgerAmount,
                           Long acquirerAmount, String detail) {
    }

    public record Run(String id, LocalDate scopeDate, String status, int paymentsChecked, int refundsChecked,
                      int acquirerLines, int mismatchCount, String error, Instant startedAt, Instant completedAt) {
    }

    record AcquirerLine(String type, String acquirerRef, String reference, long amount, String currency, Instant at) {
    }

    private record Payment(String id, String currency, long amount, String status, String method, Instant succeededAt,
                           Instant ingestedAt) {
    }

    private record Findings(List<Mismatch> mismatches, int payments, int refunds) {
    }

    private final JdbcClient jdbc;
    private final RestClient acquirer;
    private final Duration settleWait;
    private final AtomicInteger latestMismatches = new AtomicInteger();

    Reconciliation(JdbcClient jdbc, RestClient.Builder builder, MeterRegistry meters,
                   @Value("${cutpay.reconciliation.acquirer-url}") String acquirerUrl,
                   @Value("${cutpay.reconciliation.settle-wait:PT10S}") Duration settleWait) {
        this.jdbc = jdbc;
        this.acquirer = builder.baseUrl(acquirerUrl).build();
        this.settleWait = settleWait;
        Gauge.builder("cutpay.reconciliation.mismatches", latestMismatches, AtomicInteger::get)
                .description("Differences found by the latest reconciliation run").register(meters);
    }

    /** @param date the UTC day to compare, or null for everything the acquirer file holds */
    public Run run(LocalDate date) {
        String id = Ids.generate("rec");
        jdbc.sql("INSERT INTO reconciliation_runs (id, scope_date, status) VALUES (:id, :date, 'running')")
                .param("id", id).param("date", date, java.sql.Types.DATE).update();
        try {
            List<AcquirerLine> lines = fetch(date);
            Findings found = compare(lines, date);
            if (!found.mismatches().isEmpty() && !settleWait.isZero()) {
                sleep(settleWait);
                lines = fetch(date);
                found = compare(lines, date);
            }
            for (Mismatch m : found.mismatches()) {
                jdbc.sql("""
                                INSERT INTO reconciliation_mismatches (run_id, kind, payment_id, currency, payments_amount, ledger_amount,
                                                                       acquirer_amount, detail)
                                VALUES (:run, :kind, :payment, :currency, :p, :l, :a, :detail)
                                """)
                        .param("run", id).param("kind", m.kind()).param("payment", m.paymentId())
                        .param("currency", m.currency(), java.sql.Types.VARCHAR)
                        .param("p", m.paymentsAmount(), java.sql.Types.BIGINT).param("l", m.ledgerAmount(), java.sql.Types.BIGINT)
                        .param("a", m.acquirerAmount(), java.sql.Types.BIGINT).param("detail", m.detail()).update();
            }
            jdbc.sql("""
                            UPDATE reconciliation_runs SET status = :status, payments_checked = :payments, refunds_checked = :refunds,
                                   acquirer_lines = :lines, mismatch_count = :count, completed_at = now() WHERE id = :id
                            """)
                    .param("status", found.mismatches().isEmpty() ? "clean" : "mismatched").param("payments", found.payments())
                    .param("refunds", found.refunds()).param("lines", lines.size()).param("count", found.mismatches().size())
                    .param("id", id).update();
            latestMismatches.set(found.mismatches().size());
            log.info("Reconciliation {} checked {} payments and {} refunds against {} acquirer lines: {} differences", id,
                    found.payments(), found.refunds(), lines.size(), found.mismatches().size());
        } catch (RuntimeException e) {
            log.error("Reconciliation {} failed", id, e);
            jdbc.sql("UPDATE reconciliation_runs SET status = 'failed', error = :error, completed_at = now() WHERE id = :id")
                    .param("error", String.valueOf(e.getMessage())).param("id", id).update();
        }
        return get(id);
    }

    public Run get(String id) {
        return jdbc.sql("SELECT * FROM reconciliation_runs WHERE id = :id").param("id", id).query(Reconciliation::run).single();
    }

    public List<Run> latest(int limit) {
        return jdbc.sql("SELECT * FROM reconciliation_runs ORDER BY started_at DESC LIMIT :limit").param("limit", limit)
                .query(Reconciliation::run).list();
    }

    public List<Mismatch> mismatches(String runId) {
        return jdbc.sql("SELECT * FROM reconciliation_mismatches WHERE run_id = :run ORDER BY id").param("run", runId)
                .query((rs, n) -> new Mismatch(rs.getString("kind"), rs.getString("payment_id"), rs.getString("currency"),
                        (Long) rs.getObject("payments_amount"), (Long) rs.getObject("ledger_amount"),
                        (Long) rs.getObject("acquirer_amount"), rs.getString("detail")))
                .list();
    }

    private static Run run(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Date scope = rs.getDate("scope_date");
        java.sql.Timestamp done = rs.getTimestamp("completed_at");
        return new Run(rs.getString("id"), scope == null ? null : scope.toLocalDate(), rs.getString("status"),
                rs.getInt("payments_checked"), rs.getInt("refunds_checked"), rs.getInt("acquirer_lines"),
                rs.getInt("mismatch_count"), rs.getString("error"), rs.getTimestamp("started_at").toInstant(),
                done == null ? null : done.toInstant());
    }

    // --- the comparison ------------------------------------------------------------------------------------------

    private Findings compare(List<AcquirerLine> lines, LocalDate date) {
        boolean acquirerKnown = !lines.isEmpty();
        // Without a date, only payments seen since the acquirer file begins can be in it.
        Instant since = date != null || lines.isEmpty() ? null
                : lines.stream().map(AcquirerLine::at).min(Instant::compareTo).orElseThrow().minus(Duration.ofMinutes(5));
        Map<String, Long> acquirerCharged = new HashMap<>();
        Map<String, Long> acquirerRefunded = new HashMap<>();
        Map<String, String> acquirerCurrency = new HashMap<>();
        for (AcquirerLine line : lines) {
            if (line.reference() == null || line.reference().isBlank()) {
                continue;
            }
            (line.type().equals("charge") ? acquirerCharged : acquirerRefunded).merge(line.reference(), line.amount(), Long::sum);
            acquirerCurrency.putIfAbsent(line.reference(), line.currency());
        }

        Map<String, Payment> payments = new LinkedHashMap<>();
        jdbc.sql("SELECT payment_id, currency, amount, status, method_type, succeeded_at, ingested_at FROM fact_payments ORDER BY created_at")
                .query((rs, n) -> {
                    java.sql.Timestamp at = rs.getTimestamp("succeeded_at");
                    return new Payment(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                            at == null ? null : at.toInstant(), instant(rs.getTimestamp("ingested_at")));
                }).list().forEach(p -> payments.put(p.id(), p));
        Map<String, Long> ledgerCharged = new HashMap<>();
        jdbc.sql("SELECT source_id, SUM(gross) FROM fact_balance_transactions WHERE type = 'payment' GROUP BY source_id")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .forEach(e -> ledgerCharged.put(e.getKey(), e.getValue()));
        Map<String, Long> ledgerRefunded = new HashMap<>();
        jdbc.sql("SELECT parent_id, SUM(-gross) FROM fact_balance_transactions WHERE type = 'refund' AND parent_id IS NOT NULL GROUP BY parent_id")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .forEach(e -> ledgerRefunded.put(e.getKey(), e.getValue()));

        List<Mismatch> out = new ArrayList<>();
        int checked = 0;
        Set<String> refundsChecked = new HashSet<>();
        for (Payment p : payments.values()) {
            boolean bnpl = "bnpl".equals(p.method());
            if (SETTLED.contains(p.status()) && inScope(p, date, since)) {
                checked++;
                Long ledger = ledgerCharged.get(p.id());
                if (ledger == null) {
                    out.add(new Mismatch("missing_in_ledger", p.id(), p.currency(), p.amount(), null, acquirerCharged.get(p.id()),
                            "Payments says it succeeded but the ledger booked nothing"));
                } else if (ledger != p.amount()) {
                    out.add(new Mismatch("ledger_amount_differs", p.id(), p.currency(), p.amount(), ledger,
                            acquirerCharged.get(p.id()), "The ledger booked a different amount than the payment"));
                }
                if (acquirerKnown && !bnpl) {
                    Long charged = acquirerCharged.get(p.id());
                    if (charged == null) {
                        out.add(new Mismatch("missing_at_acquirer", p.id(), p.currency(), p.amount(), ledger, null,
                                "Payments says it succeeded but the acquirer has no charge"));
                    } else if (charged != p.amount()) {
                        out.add(new Mismatch("acquirer_amount_differs", p.id(), p.currency(), p.amount(), ledger, charged,
                                "The acquirer charged a different amount than the payment"));
                    }
                }
            }
            if (inScope(p, date, since) && (ledgerRefunded.containsKey(p.id()) || acquirerRefunded.containsKey(p.id()))) {
                refundsChecked.add(p.id());
                long inLedger = ledgerRefunded.getOrDefault(p.id(), 0L);
                long atAcquirer = acquirerRefunded.getOrDefault(p.id(), 0L);
                if (!bnpl && acquirerKnown && inLedger != atAcquirer) {
                    out.add(new Mismatch("refund_total_differs", p.id(), p.currency(), p.amount(), inLedger, atAcquirer,
                            "Refunds booked in the ledger and refunds paid by the acquirer do not add up to the same"));
                }
            }
            if (acquirerKnown && !bnpl && NEVER_CHARGED.contains(p.status()) && acquirerCharged.getOrDefault(p.id(), 0L) > 0) {
                out.add(new Mismatch("charged_but_not_succeeded", p.id(), p.currency(), p.amount(), ledgerCharged.get(p.id()),
                        acquirerCharged.get(p.id()), "The acquirer charged the card but the payment is " + p.status()));
            }
        }
        for (Map.Entry<String, Long> charge : acquirerCharged.entrySet()) {
            if (!payments.containsKey(charge.getKey()) && charge.getValue() > 0) {
                out.add(new Mismatch("unknown_payment", charge.getKey(), acquirerCurrency.get(charge.getKey()), null,
                        ledgerCharged.get(charge.getKey()), charge.getValue(), "The acquirer charged a payment nobody has a record of"));
            }
        }
        return new Findings(out, checked, refundsChecked.size());
    }

    private static boolean inScope(Payment p, LocalDate date, Instant since) {
        if (date == null) {
            return since == null || (p.ingestedAt() != null && !p.ingestedAt().isBefore(since));
        }
        return p.succeededAt() != null && p.succeededAt().atZone(ZoneOffset.UTC).toLocalDate().equals(date);
    }

    private List<AcquirerLine> fetch(LocalDate date) {
        String csv = acquirer.get().uri(uri -> {
                    var b = uri.path("/settlement");
                    return date == null ? b.build() : b.queryParam("date", date).build();
                }).accept(MediaType.parseMediaType("text/csv")).retrieve().body(String.class);
        List<AcquirerLine> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        String[] rows = csv.split("\\R");
        for (int i = 1; i < rows.length; i++) {
            if (rows[i].isBlank()) {
                continue;
            }
            String[] f = rows[i].split(",", -1);
            out.add(new AcquirerLine(f[0], f[1], f[2], Long.parseLong(f[3]), f[4], Instant.parse(f[5])));
        }
        return out;
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
