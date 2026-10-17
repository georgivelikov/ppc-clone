package dev.cutpay.webhooks.delivery;

import dev.cutpay.web.ApiException;
import dev.cutpay.webhooks.config.Caller;
import dev.cutpay.webhooks.config.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The delivery log: what was sent to which endpoint, how it went, and a button to send it again. */
@RestController
class DeliveryController {

    private static final int MAX_PAGE = 100;

    record DeliveryView(String id, String object, String eventId, String eventType, String endpointId,
                        String status, int attempts, Instant nextAttemptAt, Integer lastResponseCode,
                        boolean livemode, Instant createdAt, List<AttemptView> attemptLog) {
    }

    record AttemptView(int attempt, Integer responseCode, String responseBodySnippet, String error,
                       long durationMs, Instant createdAt) {
    }

    record DeliveryList(String object, List<DeliveryView> data, boolean hasMore) {
    }

    private static final String SELECT = """
            SELECT d.id, d.event_id, e.type AS event_type, d.endpoint_id, d.status, d.attempts, d.next_attempt_at,
                   d.last_response_code, d.livemode, d.created_at
              FROM webhook_deliveries d JOIN events e ON e.id = d.event_id
            """;

    private final JdbcClient jdbc;
    private final DeliveryService deliveries;

    DeliveryController(JdbcClient jdbc, DeliveryService deliveries) {
        this.jdbc = jdbc;
        this.deliveries = deliveries;
    }

    @GetMapping("/v1/webhook_deliveries")
    DeliveryList list(@AuthenticationPrincipal Jwt jwt,
                      @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                      @RequestParam(required = false) String endpoint,
                      @RequestParam(required = false) String event,
                      @RequestParam(required = false) String status,
                      @RequestParam(name = "starting_after", required = false) String startingAfter,
                      @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalidRequest("invalid_limit", "limit must be between 1 and " + MAX_PAGE, "limit");
        }
        Caller caller = Caller.of(jwt, requestedLivemode);
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" WHERE d.merchant_id = :merchant AND d.livemode = :livemode");
        Map<String, Object> params = new HashMap<>();
        params.put("merchant", caller.merchantId());
        params.put("livemode", caller.livemode());
        if (endpoint != null && !endpoint.isBlank()) {
            sql.append(" AND d.endpoint_id = :endpoint");
            params.put("endpoint", endpoint);
        }
        if (event != null && !event.isBlank()) {
            sql.append(" AND d.event_id = :event");
            params.put("event", event);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND d.status = :status");
            params.put("status", status);
        }
        if (startingAfter != null && !startingAfter.isBlank()) {
            sql.append(" AND d.id < :after");
            params.put("after", startingAfter);
        }
        sql.append(" ORDER BY d.id DESC LIMIT :limit");
        params.put("limit", limit + 1);
        List<DeliveryView> rows = new ArrayList<>(
                jdbc.sql(sql.toString()).params(params).query((rs, n) -> view(rs, null)).list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows.remove(rows.size() - 1);
        }
        return new DeliveryList("list", rows, hasMore);
    }

    @GetMapping("/v1/webhook_deliveries/{id}")
    DeliveryView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return find(Caller.of(jwt, false), id);
    }

    /** Sends the delivery again right away, whatever its state; later failures follow the normal schedule. */
    @PostMapping("/v1/webhook_deliveries/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeliveryView retry(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        Caller caller = Caller.of(jwt, false);
        caller.requireWrite();
        DeliveryView delivery = find(caller, id);
        String endpointStatus = jdbc.sql("SELECT status FROM webhook_endpoints WHERE id = :id")
                .param("id", delivery.endpointId()).query(String.class).single();
        if (!endpointStatus.equals("enabled")) {
            throw ApiException.invalidRequest("endpoint_disabled", "Enable the endpoint before resending", null);
        }
        deliveries.retryNow(id, caller.merchantId(), caller.claimedMode());
        return find(caller, id);
    }

    private DeliveryView find(Caller caller, String id) {
        DeliveryView delivery = jdbc.sql(SELECT + " WHERE d.id = :id AND d.merchant_id = :merchant"
                        + caller.modeClause("d.livemode"))
                .param("id", id)
                .params(caller.params())
                .query((rs, n) -> view(rs, List.of()))
                .optional()
                .orElseThrow(() -> ApiException.notFound("webhook_delivery_not_found", "No such delivery"));
        List<AttemptView> attempts = jdbc.sql("""
                        SELECT attempt, response_code, response_body_snippet, error, duration_ms, created_at
                          FROM delivery_attempts WHERE delivery_id = :id ORDER BY attempt
                        """)
                .param("id", id)
                .query((rs, n) -> new AttemptView(rs.getInt("attempt"), (Integer) rs.getObject("response_code"),
                        rs.getString("response_body_snippet"), rs.getString("error"), rs.getLong("duration_ms"),
                        Sql.instant(rs, "created_at")))
                .list();
        return new DeliveryView(delivery.id(), delivery.object(), delivery.eventId(), delivery.eventType(),
                delivery.endpointId(), delivery.status(), delivery.attempts(), delivery.nextAttemptAt(),
                delivery.lastResponseCode(), delivery.livemode(), delivery.createdAt(), attempts);
    }

    private static DeliveryView view(ResultSet rs, List<AttemptView> attemptLog) throws SQLException {
        return new DeliveryView(rs.getString("id"), "webhook_delivery", rs.getString("event_id"),
                rs.getString("event_type"), rs.getString("endpoint_id"), rs.getString("status"),
                rs.getInt("attempts"), Sql.instant(rs, "next_attempt_at"),
                (Integer) rs.getObject("last_response_code"), rs.getBoolean("livemode"),
                Sql.instant(rs, "created_at"), attemptLog);
    }
}
