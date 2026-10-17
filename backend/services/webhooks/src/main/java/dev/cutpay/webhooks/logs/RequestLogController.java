package dev.cutpay.webhooks.logs;

import dev.cutpay.web.ApiException;
import dev.cutpay.webhooks.config.Caller;
import dev.cutpay.webhooks.config.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Developers, Logs: what the merchant integration has been sending, newest first. */
@RestController
class RequestLogController {

    private static final int MAX_PAGE = 100;

    record LogView(String id, String object, String method, String path, int status, long latencyMs, String actor,
                   String actorType, String idempotencyKey, String requestId, boolean livemode, Instant createdAt) {
    }

    record LogList(String object, List<LogView> data, boolean hasMore) {
    }

    private static final String SELECT = """
            SELECT id, method, path, status, latency_ms, actor, actor_type, idempotency_key, request_id, livemode,
                   created_at
              FROM api_requests
            """;

    private final JdbcClient jdbc;

    RequestLogController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Filters: {@code status} is an exact code or a class such as {@code 4xx}; {@code path} matches the start
     * of the path; {@code actor} is a key or user id.
     */
    @GetMapping("/v1/logs")
    LogList list(@AuthenticationPrincipal Jwt jwt,
                 @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                 @RequestParam(required = false) String method,
                 @RequestParam(required = false) String status,
                 @RequestParam(required = false) String path,
                 @RequestParam(required = false) String actor,
                 @RequestParam(name = "starting_after", required = false) String startingAfter,
                 @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalidRequest("invalid_limit", "limit must be between 1 and " + MAX_PAGE, "limit");
        }
        Caller caller = Caller.of(jwt, requestedLivemode);
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE merchant_id = :merchant AND livemode = :livemode");
        Map<String, Object> params = new HashMap<>();
        params.put("merchant", caller.merchantId());
        params.put("livemode", caller.livemode());
        if (method != null && !method.isBlank()) {
            sql.append(" AND method = :method");
            params.put("method", method.toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank()) {
            if (status.matches("[1-5]xx")) {
                sql.append(" AND status >= :from AND status < :to");
                params.put("from", (status.charAt(0) - '0') * 100);
                params.put("to", (status.charAt(0) - '0' + 1) * 100);
            } else if (status.matches("[0-9]{3}")) {
                sql.append(" AND status = :status");
                params.put("status", Integer.parseInt(status));
            } else {
                throw ApiException.invalidRequest("invalid_status", "status must be like 404 or 4xx", "status");
            }
        }
        if (path != null && !path.isBlank()) {
            // The user text is escaped so that % and _ are matched literally.
            sql.append(" AND path LIKE :path ESCAPE '!'");
            params.put("path", path.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor = :actor");
            params.put("actor", actor);
        }
        if (startingAfter != null && !startingAfter.isBlank()) {
            sql.append(" AND id < :after");
            params.put("after", startingAfter);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", limit + 1);
        List<LogView> rows = new ArrayList<>(
                jdbc.sql(sql.toString()).params(params).query((rs, n) -> view(rs)).list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows.remove(rows.size() - 1);
        }
        return new LogList("list", rows, hasMore);
    }

    @GetMapping("/v1/logs/{id}")
    LogView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        Caller caller = Caller.of(jwt, false);
        return jdbc.sql(SELECT + " WHERE id = :id AND merchant_id = :merchant" + caller.modeClause("livemode"))
                .param("id", id)
                .params(caller.params())
                .query((rs, n) -> view(rs))
                .optional()
                .orElseThrow(() -> ApiException.notFound("log_not_found", "No such log entry"));
    }

    private static LogView view(ResultSet rs) throws SQLException {
        return new LogView(rs.getString("id"), "api_request", rs.getString("method"), rs.getString("path"),
                rs.getInt("status"), rs.getLong("latency_ms"), rs.getString("actor"), rs.getString("actor_type"),
                rs.getString("idempotency_key"), rs.getString("request_id"), rs.getBoolean("livemode"),
                Sql.instant(rs, "created_at"));
    }
}
