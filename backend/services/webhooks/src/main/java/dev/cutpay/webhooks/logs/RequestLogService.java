package dev.cutpay.webhooks.logs;

import dev.cutpay.events.InboxGuard;
import dev.cutpay.web.Ids;
import dev.cutpay.webhooks.config.Sql;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Stores request-log events from the gateway and drops the ones older than the retention period. */
@Service
public class RequestLogService {

    public static final Duration RETENTION = Duration.ofDays(30);

    private final JdbcClient jdbc;
    private final InboxGuard inbox;
    private final Clock clock;

    public RequestLogService(JdbcClient jdbc, InboxGuard inbox, Clock clock) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.clock = clock;
    }

    @Transactional
    public void ingest(String eventId, String merchantId, boolean livemode, Instant occurredAt, JsonNode p) {
        if (!inbox.firstTime(eventId)) {
            return;
        }
        // Ids are generated from the request time, so the log sorts by when the call happened.
        String id = Ids.generate("rl", Clock.fixed(occurredAt, ZoneOffset.UTC));
        jdbc.sql("""
                        INSERT INTO api_requests (id, source_event_id, merchant_id, livemode, method, path, status,
                                                  latency_ms, actor, actor_type, idempotency_key, request_id,
                                                  created_at)
                        VALUES (:id, :source, :merchant, :livemode, :method, :path, :status, :latency, :actor,
                                :actorType, :idem, :requestId, :createdAt)
                        ON CONFLICT (source_event_id) DO NOTHING
                        """)
                .param("id", id).param("source", eventId).param("merchant", merchantId).param("livemode", livemode)
                .param("method", required(p, "method")).param("path", required(p, "path"))
                .param("status", p.path("status").asInt()).param("latency", p.path("latency_ms").asLong())
                .param("actor", optional(p, "actor")).param("actorType", optional(p, "actor_type"))
                .param("idem", optional(p, "idempotency_key")).param("requestId", optional(p, "request_id"))
                .param("createdAt", Sql.ts(occurredAt))
                .update();
    }

    /** Deletes entries past the retention period, measured on the service clock. Returns how many. */
    public int deleteExpired() {
        return jdbc.sql("DELETE FROM api_requests WHERE created_at < :cutoff")
                .param("cutoff", Sql.ts(clock.instant().minus(RETENTION))).update();
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Event is missing required field " + field);
        }
        return value.asString();
    }

    private static String optional(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
