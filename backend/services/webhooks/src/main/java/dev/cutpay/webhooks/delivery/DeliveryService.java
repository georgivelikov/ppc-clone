package dev.cutpay.webhooks.delivery;

import dev.cutpay.web.Ids;
import dev.cutpay.webhooks.config.Sql;
import dev.cutpay.webhooks.config.WebhookProperties;
import dev.cutpay.webhooks.delivery.WebhookSender.Result;
import dev.cutpay.webhooks.signature.WebhookSignature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued deliveries and records what happened (docs/adr/0010-webhook-delivery.md).
 *
 * <p>The queue is the {@code webhook_deliveries} table. A worker claims due rows with
 * {@code FOR UPDATE SKIP LOCKED} and pushes their {@code next_attempt_at} out by a lease, so several instances
 * can run side by side without sending twice, and a worker that dies mid-send only delays the delivery by the
 * lease. The HTTP call itself happens outside any database transaction.
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final WebhookSender sender;
    private final WebhookProperties properties;

    public DeliveryService(JdbcClient jdbc, TransactionTemplate tx, Clock clock, WebhookSender sender,
                           WebhookProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.sender = sender;
        this.properties = properties;
    }

    /** Claims and sends everything that is due, concurrently. Returns how many deliveries were processed. */
    public int processDue() {
        List<String> claimed = claimDue();
        if (claimed.isEmpty()) {
            return 0;
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = claimed.stream().<Callable<Void>>map(id -> () -> {
                try {
                    deliver(id);
                } catch (RuntimeException e) {
                    // The lease expires and the delivery comes round again; one bad row must not stop the rest.
                    log.error("Could not process delivery {}", id, e);
                }
                return null;
            }).toList();
            executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return claimed.size();
    }

    List<String> claimDue() {
        return tx.execute(status -> {
            Instant now = clock.instant();
            List<String> ids = jdbc.sql("""
                            SELECT id FROM webhook_deliveries
                             WHERE status = 'pending' AND next_attempt_at <= :now
                             ORDER BY next_attempt_at
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED
                            """)
                    .param("now", Sql.ts(now))
                    .param("limit", properties.batchSize())
                    .query(String.class).list();
            for (String id : ids) {
                jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = :lease WHERE id = :id")
                        .param("lease", Sql.ts(now.plus(properties.lease())))
                        .param("id", id).update();
            }
            return ids;
        });
    }

    private record Work(String deliveryId, int attempts, String endpointId, boolean livemode, String eventId,
                        String body, Instant eventCreatedAt, String url, String secret, String endpointStatus) {
    }

    void deliver(String deliveryId) {
        Optional<Work> loaded = jdbc.sql("""
                        SELECT d.id, d.attempts, d.endpoint_id, d.livemode, e.id AS event_id, CAST(e.body AS text) AS body,
                               e.created_at AS event_created_at, w.url, w.secret, w.status AS endpoint_status
                          FROM webhook_deliveries d
                          JOIN events e ON e.id = d.event_id
                          JOIN webhook_endpoints w ON w.id = d.endpoint_id
                         WHERE d.id = :id AND d.status = 'pending'
                        """)
                .param("id", deliveryId)
                .query((rs, n) -> new Work(rs.getString("id"), rs.getInt("attempts"), rs.getString("endpoint_id"),
                        rs.getBoolean("livemode"), rs.getString("event_id"), rs.getString("body"),
                        Sql.instant(rs, "event_created_at"), rs.getString("url"), rs.getString("secret"),
                        rs.getString("endpoint_status")))
                .optional();
        if (loaded.isEmpty()) {
            return;
        }
        Work work = loaded.get();
        if (!work.endpointStatus().equals("enabled")) {
            // Disabled or deleted while the delivery waited: stop. After re-enabling, it can be resent by hand.
            jdbc.sql("UPDATE webhook_deliveries SET status = 'failed', next_attempt_at = NULL WHERE id = :id")
                    .param("id", work.deliveryId()).update();
            return;
        }
        String signature = WebhookSignature.sign(work.secret(), work.body(), clock.instant());
        Result result = sender.send(work.url(), work.body(), signature, work.eventId(), work.livemode());
        tx.executeWithoutResult(status -> record(work, result));
    }

    private void record(Work work, Result result) {
        Instant now = clock.instant();
        int attempt = work.attempts() + 1;
        jdbc.sql("""
                        INSERT INTO delivery_attempts (id, delivery_id, attempt, response_code, response_body_snippet,
                                                       error, duration_ms, created_at)
                        VALUES (:id, :delivery, :attempt, :code, :snippet, :error, :duration, :now)
                        """)
                .param("id", Ids.generate("wha")).param("delivery", work.deliveryId()).param("attempt", attempt)
                .param("code", result.status()).param("snippet", result.snippet()).param("error", result.error())
                .param("duration", result.durationMs()).param("now", Sql.ts(now))
                .update();

        if (result.succeeded()) {
            jdbc.sql("""
                            UPDATE webhook_deliveries
                               SET status = 'succeeded', attempts = :attempt, next_attempt_at = NULL,
                                   last_response_code = :code
                             WHERE id = :id
                            """)
                    .param("attempt", attempt).param("code", result.status()).param("id", work.deliveryId()).update();
            jdbc.sql("UPDATE webhook_endpoints SET failing_since = NULL WHERE id = :id")
                    .param("id", work.endpointId()).update();
            return;
        }

        Optional<Instant> next = RetrySchedule.nextAttempt(attempt, now, work.eventCreatedAt(),
                properties.giveUpAfter());
        jdbc.sql("""
                        UPDATE webhook_deliveries
                           SET status = :status, attempts = :attempt, next_attempt_at = :next, last_response_code = :code
                         WHERE id = :id
                        """)
                .param("status", next.isPresent() ? "pending" : "failed")
                .param("attempt", attempt).param("next", Sql.ts(next.orElse(null)))
                .param("code", result.status()).param("id", work.deliveryId()).update();

        OffsetDateTime failingSince = jdbc.sql("""
                        UPDATE webhook_endpoints SET failing_since = COALESCE(failing_since, :now)
                         WHERE id = :id RETURNING failing_since
                        """)
                .param("now", Sql.ts(now)).param("id", work.endpointId())
                .query(OffsetDateTime.class).single();
        if (!failingSince.toInstant().plus(properties.disableAfter()).isAfter(now)) {
            int disabled = jdbc.sql("""
                            UPDATE webhook_endpoints SET status = 'disabled', disabled_reason = :reason
                             WHERE id = :id AND status = 'enabled'
                            """)
                    .param("reason", "Disabled automatically after failing for "
                            + properties.disableAfter().toDays() + " days")
                    .param("id", work.endpointId()).update();
            if (disabled > 0) {
                log.warn("Disabled webhook endpoint {} after continuous failures", work.endpointId());
            }
        }
    }

    /** Puts a delivery back in the queue now. Returns false when it does not exist for this caller. */
    public boolean retryNow(String deliveryId, String merchantId, Boolean claimedMode) {
        String modeClause = claimedMode == null ? "" : " AND livemode = :mode";
        var statement = jdbc.sql("""
                        UPDATE webhook_deliveries SET status = 'pending', next_attempt_at = :now
                         WHERE id = :id AND merchant_id = :merchant
                        """ + modeClause)
                .param("now", Sql.ts(clock.instant())).param("id", deliveryId).param("merchant", merchantId);
        if (claimedMode != null) {
            statement = statement.param("mode", claimedMode);
        }
        return statement.update() > 0;
    }
}
