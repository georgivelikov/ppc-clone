package dev.cutpay.payments.dispute;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.payments.acquirer.AcquirerClient;
import dev.cutpay.payments.config.KafkaTopicsConfig;
import dev.cutpay.payments.domain.PaymentAttempt;
import dev.cutpay.payments.domain.PaymentIntent;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Disputes (chargebacks), start to end.
 *
 * <p>Lifecycle: {@code needs_response} until the merchant sends evidence or the deadline passes, then
 * {@code under_review} while the card network decides, then {@code won} or {@code lost}. Opening one takes the disputed
 * amount and a fee from the merchant (the ledger does that when it hears {@code DisputeOpened}); winning gives the
 * amount and the fee back; losing means the customer keeps the money.
 */
@Service
public class DisputeService {

    public static final Duration EVIDENCE_WINDOW = Duration.ofDays(7);
    static final int MAX_FILES = 5;
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final String REASON = "fraudulent";

    /** What a dispute costs, in the smallest unit of the currency: about 15 euros. */
    private static final Map<String, Long> FEE = Map.of("EUR", 1500L, "USD", 1500L, "GBP", 1300L, "BGN", 3000L,
            "RON", 7500L, "PLN", 6500L, "HUF", 600_000L, "CZK", 37_500L);

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final AcquirerClient acquirer;
    private final Duration reviewDelay;

    public DisputeService(JdbcClient jdbc, OutboxWriter outbox, Clock clock, AcquirerClient acquirer,
                          @Value("${cutpay.payments.dispute-review-delay:PT5S}") Duration reviewDelay) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
        this.acquirer = acquirer;
        this.reviewDelay = reviewDelay;
    }

    public record Dispute(String id, String merchantId, boolean livemode, String paymentId, long amount, String currency,
                          long fee, String reason, String status, String evidenceText, Instant evidenceDueBy,
                          Instant evidenceSubmittedAt, String outcomeReason, Instant openedAt, Instant closedAt,
                          List<FileInfo> files) {
    }

    public record FileInfo(String id, String filename, String contentType, int size, Instant uploadedAt) {
    }

    public record FileContent(String filename, String contentType, byte[] content) {
    }

    // --- scheduling and opening ---------------------------------------------------------------------------------

    /** Called when a payment succeeds: remember that its cardholder will dispute it. */
    @Transactional
    public void scheduleIfPlanned(PaymentIntent intent, PaymentAttempt attempt) {
        if (attempt == null || attempt.getDisputeAfterDays() == null) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO dispute_schedule (payment_id, open_at) VALUES (:payment, :openAt)
                        ON CONFLICT (payment_id) DO NOTHING
                        """)
                .param("payment", intent.getId())
                .param("openAt", Timestamp.from(clock.instant().plus(Duration.ofDays(attempt.getDisputeAfterDays()))))
                .update();
    }

    /** Opens the disputes whose day has come. Returns how many. */
    @Transactional
    public int openDue(int limit) {
        record Due(String paymentId, String merchantId, boolean livemode, long amount, String currency) {
        }
        List<Due> due = jdbc.sql("""
                        SELECT s.payment_id, p.merchant_id, p.livemode, p.amount - p.amount_refunded AS disputed, p.currency
                        FROM dispute_schedule s JOIN payment_intents p ON p.id = s.payment_id
                        WHERE s.opened_at IS NULL AND s.open_at <= :now
                        ORDER BY s.open_at LIMIT :limit FOR UPDATE OF s SKIP LOCKED
                        """)
                .param("now", Timestamp.from(clock.instant())).param("limit", limit)
                .query((rs, n) -> new Due(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getLong(4), rs.getString(5)))
                .list();
        Instant now = clock.instant();
        for (Due d : due) {
            jdbc.sql("UPDATE dispute_schedule SET opened_at = :now WHERE payment_id = :p")
                    .param("now", Timestamp.from(now)).param("p", d.paymentId()).update();
            if (d.amount() <= 0) {
                continue; // refunded in full before the cardholder disputed it
            }
            String id = Ids.generate("dp");
            long fee = FEE.getOrDefault(d.currency(), 1500L);
            jdbc.sql("""
                            INSERT INTO disputes (id, merchant_id, livemode, payment_id, amount, currency, fee, reason, status,
                                                  evidence_due_by, opened_at)
                            VALUES (:id, :merchant, :livemode, :payment, :amount, :currency, :fee, :reason, 'needs_response',
                                    :due, :now)
                            """)
                    .param("id", id).param("merchant", d.merchantId()).param("livemode", d.livemode())
                    .param("payment", d.paymentId()).param("amount", d.amount()).param("currency", d.currency())
                    .param("fee", fee).param("reason", REASON)
                    .param("due", Timestamp.from(now.plus(EVIDENCE_WINDOW))).param("now", Timestamp.from(now))
                    .update();
            emit("DisputeOpened", get(id).orElseThrow());
        }
        return due.size();
    }

    // --- the merchant's side --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<Dispute> find(String merchantId, boolean livemode, String id) {
        return get(id).filter(d -> d.merchantId().equals(merchantId) && d.livemode() == livemode);
    }

    @Transactional(readOnly = true)
    public List<Dispute> list(String merchantId, boolean livemode, String status, String paymentId, String startingAfter,
                              int limit) {
        List<String> ids = jdbc.sql("""
                        SELECT id FROM disputes
                        WHERE merchant_id = :merchant AND livemode = :livemode
                          AND (CAST(:status AS TEXT) IS NULL OR status = :status)
                          AND (CAST(:payment AS TEXT) IS NULL OR payment_id = :payment)
                          AND (CAST(:after AS TEXT) IS NULL OR id < :after)
                        ORDER BY id DESC LIMIT :limit
                        """)
                .param("merchant", merchantId).param("livemode", livemode)
                .param("status", status, java.sql.Types.VARCHAR).param("payment", paymentId, java.sql.Types.VARCHAR)
                .param("after", startingAfter, java.sql.Types.VARCHAR).param("limit", limit)
                .query(String.class).list();
        return ids.stream().map(id -> get(id).orElseThrow()).toList();
    }

    /** Sends the written evidence to be reviewed. Evidence can be sent once. */
    @Transactional
    public Dispute submitEvidence(String merchantId, boolean livemode, String id, String text) {
        Dispute d = lock(merchantId, livemode, id);
        if (!"needs_response".equals(d.status())) {
            throw ApiException.conflict("dispute_not_open", "This dispute is no longer waiting for evidence", null);
        }
        if (!clock.instant().isBefore(d.evidenceDueBy())) {
            throw ApiException.conflict("evidence_overdue", "The deadline to send evidence has passed", null);
        }
        int files = fileCount(id);
        if ((text == null || text.isBlank()) && files == 0) {
            throw ApiException.invalidRequest("evidence_required", "Write your evidence or attach a file", "evidence");
        }
        jdbc.sql("""
                        UPDATE disputes SET status = 'under_review', evidence_text = :text,
                               evidence_submitted_at = :now, review_started_at = now()
                        WHERE id = :id
                        """)
                .param("text", text == null ? null : text.trim()).param("now", Timestamp.from(clock.instant()))
                .param("id", id).update();
        Dispute updated = get(id).orElseThrow();
        emit("DisputeEvidenceSubmitted", updated);
        return updated;
    }

    @Transactional
    public FileInfo addFile(String merchantId, boolean livemode, String id, String filename, String contentType, byte[] content) {
        Dispute d = lock(merchantId, livemode, id);
        if (!"needs_response".equals(d.status())) {
            throw ApiException.conflict("dispute_not_open", "Files can only be added before the evidence is sent", null);
        }
        if (content.length == 0 || content.length > MAX_FILE_BYTES) {
            throw ApiException.invalidRequest("invalid_file", "A file must be between 1 byte and 2 MB", "file");
        }
        if (fileCount(id) >= MAX_FILES) {
            throw ApiException.invalidRequest("too_many_files", "At most " + MAX_FILES + " files can be attached", "file");
        }
        String fileId = Ids.generate("df");
        String safeName = filename == null || filename.isBlank() ? "evidence" : filename.replaceAll("[^A-Za-z0-9._ -]", "_");
        jdbc.sql("""
                        INSERT INTO dispute_files (id, dispute_id, filename, content_type, size, content)
                        VALUES (:id, :dispute, :name, :type, :size, :content)
                        """)
                .param("id", fileId).param("dispute", id).param("name", safeName)
                .param("type", contentType == null ? "application/octet-stream" : contentType)
                .param("size", content.length).param("content", content).update();
        return files(id).stream().filter(f -> f.id().equals(fileId)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<FileContent> file(String merchantId, boolean livemode, String id, String fileId) {
        if (find(merchantId, livemode, id).isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT filename, content_type, content FROM dispute_files WHERE id = :f AND dispute_id = :d")
                .param("f", fileId).param("d", id)
                .query((rs, n) -> new FileContent(rs.getString(1), rs.getString(2), rs.getBytes(3))).optional();
    }

    /** Gives up: the cardholder keeps the money. */
    @Transactional
    public Dispute accept(String merchantId, boolean livemode, String id) {
        Dispute d = lock(merchantId, livemode, id);
        if (!"needs_response".equals(d.status())) {
            throw ApiException.conflict("dispute_not_open", "This dispute is already being decided or closed", null);
        }
        return close(id, "lost", "You accepted the dispute.");
    }

    // --- jobs -------------------------------------------------------------------------------------------------------

    /** A dispute nobody answered by its deadline is lost. */
    @Transactional
    public int closeOverdue(int limit) {
        List<String> ids = jdbc.sql("""
                        SELECT id FROM disputes WHERE status = 'needs_response' AND evidence_due_by <= :now
                        ORDER BY evidence_due_by LIMIT :limit FOR UPDATE SKIP LOCKED
                        """)
                .param("now", Timestamp.from(clock.instant())).param("limit", limit).query(String.class).list();
        ids.forEach(id -> close(id, "lost", "No evidence was sent before the deadline."));
        return ids.size();
    }

    /** Asks the card network about every dispute whose review has run its course. Returns how many were decided. */
    @Transactional
    public int decideReviews(int limit) {
        List<Dispute> ready = jdbc.sql("""
                        SELECT id FROM disputes WHERE status = 'under_review' AND review_started_at <= :ready
                        ORDER BY review_started_at LIMIT :limit FOR UPDATE SKIP LOCKED
                        """)
                .param("ready", Timestamp.from(Instant.now().minus(reviewDelay))).param("limit", limit)
                .query(String.class).list().stream().map(id -> get(id).orElseThrow()).toList();
        int decided = 0;
        for (Dispute d : ready) {
            var verdict = acquirer.evaluateDispute(d.id(), d.evidenceText(), d.files().size());
            if (verdict.isEmpty()) {
                continue; // the network did not answer: try again on the next pass
            }
            close(d.id(), verdict.get().outcome(), verdict.get().reason());
            decided++;
        }
        return decided;
    }

    // --- internals ----------------------------------------------------------------------------------------------------

    private Dispute close(String id, String outcome, String reason) {
        jdbc.sql("UPDATE disputes SET status = :status, outcome_reason = :reason, closed_at = :now WHERE id = :id")
                .param("status", outcome).param("reason", reason).param("now", Timestamp.from(clock.instant()))
                .param("id", id).update();
        Dispute closed = get(id).orElseThrow();
        emit("DisputeClosed", closed);
        return closed;
    }

    private Dispute lock(String merchantId, boolean livemode, String id) {
        jdbc.sql("SELECT id FROM disputes WHERE id = :id AND merchant_id = :m AND livemode = :l FOR UPDATE")
                .param("id", id).param("m", merchantId).param("l", livemode).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("dispute_not_found", "No such dispute"));
        return get(id).orElseThrow();
    }

    private int fileCount(String id) {
        return jdbc.sql("SELECT count(*) FROM dispute_files WHERE dispute_id = :d").param("d", id).query(Integer.class).single();
    }

    private List<FileInfo> files(String id) {
        return jdbc.sql("SELECT id, filename, content_type, size, uploaded_at FROM dispute_files WHERE dispute_id = :d ORDER BY uploaded_at, id")
                .param("d", id)
                .query((rs, n) -> new FileInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getTimestamp(5).toInstant()))
                .list();
    }

    private Optional<Dispute> get(String id) {
        return jdbc.sql("""
                        SELECT id, merchant_id, livemode, payment_id, amount, currency, fee, reason, status, evidence_text,
                               evidence_due_by, evidence_submitted_at, outcome_reason, opened_at, closed_at
                        FROM disputes WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new Dispute(rs.getString("id"), rs.getString("merchant_id"), rs.getBoolean("livemode"),
                        rs.getString("payment_id"), rs.getLong("amount"), rs.getString("currency"), rs.getLong("fee"),
                        rs.getString("reason"), rs.getString("status"), rs.getString("evidence_text"),
                        rs.getTimestamp("evidence_due_by").toInstant(),
                        rs.getTimestamp("evidence_submitted_at") == null ? null : rs.getTimestamp("evidence_submitted_at").toInstant(),
                        rs.getString("outcome_reason"), rs.getTimestamp("opened_at").toInstant(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(), List.of()))
                .optional()
                .map(d -> new Dispute(d.id(), d.merchantId(), d.livemode(), d.paymentId(), d.amount(), d.currency(), d.fee(),
                        d.reason(), d.status(), d.evidenceText(), d.evidenceDueBy(), d.evidenceSubmittedAt(),
                        d.outcomeReason(), d.openedAt(), d.closedAt(), files(id)));
    }

    private void emit(String type, Dispute d) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dispute_id", d.id());
        payload.put("payment_id", d.paymentId());
        payload.put("amount", d.amount());
        payload.put("currency", d.currency());
        payload.put("fee", d.fee());
        payload.put("reason", d.reason());
        payload.put("status", d.status());
        payload.put("evidence_due_by", d.evidenceDueBy().toString());
        if ("won".equals(d.status()) || "lost".equals(d.status())) {
            payload.put("outcome", d.status());
            payload.put("outcome_reason", d.outcomeReason());
        }
        outbox.write(KafkaTopicsConfig.DISPUTE_TOPIC, type, d.id(), d.merchantId(), d.livemode(), payload);
    }
}
