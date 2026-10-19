package dev.cutpay.identity.audit;

import dev.cutpay.events.OutboxWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit trail: one row per sensitive action, and the same fact on the {@code audit.events} topic for anyone
 * who wants to react to it (notifications, later). Written in the caller's transaction, so an action and its
 * record stand or fall together.
 */
@Component
public class AuditLog {

    public static final String TOPIC = "audit.events";

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public AuditLog(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public record Entry(long id, String actorId, String actorEmail, String action, String targetType,
                        String targetId, String detail, Instant createdAt) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String merchantId, Jwt actor, String action, String targetType, String targetId,
                       String detail) {
        record(merchantId, actor.getSubject(), actor.getClaimAsString("email"), action, targetType, targetId, detail);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String merchantId, String actorId, String actorEmail, String action, String targetType,
                       String targetId, String detail) {
        long id = jdbc.sql("""
                        INSERT INTO audit_events (merchant_id, actor_id, actor_email, action, target_type, target_id, detail)
                        VALUES (:merchant, :actorId, :actorEmail, :action, :targetType, :targetId, :detail)
                        RETURNING id
                        """)
                .param("merchant", merchantId)
                .param("actorId", actorId)
                .param("actorEmail", actorEmail)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("detail", detail)
                .query(Long.class).single();
        Map<String, Object> payload = new HashMap<>();
        payload.put("audit_id", id);
        payload.put("action", action);
        payload.put("actor_id", actorId);
        payload.put("actor_email", actorEmail);
        payload.put("target_type", targetType);
        payload.put("target_id", targetId);
        payload.put("detail", detail);
        outbox.write(TOPIC, "AuditRecorded", merchantId, merchantId, null, payload);
    }

    /** Newest first; pass the last id of the previous page as {@code before} to get the next one. */
    @Transactional(readOnly = true)
    public List<Entry> list(String merchantId, Long before, int limit) {
        return jdbc.sql("""
                        SELECT id, actor_id, actor_email, action, target_type, target_id, detail, created_at
                        FROM audit_events
                        WHERE merchant_id = :merchant AND (:before IS NULL OR id < :before)
                        ORDER BY id DESC LIMIT :limit
                        """)
                .param("merchant", merchantId)
                .param("before", before, java.sql.Types.BIGINT)
                .param("limit", limit)
                .query((rs, n) -> new Entry(rs.getLong("id"), rs.getString("actor_id"), rs.getString("actor_email"),
                        rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                        rs.getString("detail"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
