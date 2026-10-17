package dev.cutpay.webhooks.event;

import dev.cutpay.web.ApiException;
import dev.cutpay.webhooks.config.Caller;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The public events, exactly as they were sent to endpoints. */
@RestController
class EventController {

    private static final int MAX_PAGE = 100;

    record EventList(String object, List<JsonNode> data, boolean hasMore) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    EventController(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/v1/events")
    EventList list(@AuthenticationPrincipal Jwt jwt,
                   @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                   @RequestParam(required = false) String type,
                   @RequestParam(name = "starting_after", required = false) String startingAfter,
                   @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalidRequest("invalid_limit", "limit must be between 1 and " + MAX_PAGE, "limit");
        }
        Caller caller = Caller.of(jwt, requestedLivemode);
        StringBuilder sql = new StringBuilder("""
                SELECT CAST(body AS text) FROM events WHERE merchant_id = :merchant AND livemode = :livemode
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("merchant", caller.merchantId());
        params.put("livemode", caller.livemode());
        if (type != null && !type.isBlank()) {
            sql.append(" AND type = :type");
            params.put("type", type);
        }
        if (startingAfter != null && !startingAfter.isBlank()) {
            // Event ids are not time-ordered, so the cursor is the position of the event it names.
            sql.append(" AND (created_at, id) < (SELECT created_at, id FROM events WHERE id = :after "
                    + "AND merchant_id = :merchant)");
            params.put("after", startingAfter);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        params.put("limit", limit + 1);
        List<JsonNode> rows = new ArrayList<>(
                jdbc.sql(sql.toString()).params(params).query(String.class).list().stream()
                        .map(mapper::readTree).toList());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows.remove(rows.size() - 1);
        }
        return new EventList("list", rows, hasMore);
    }

    @GetMapping("/v1/events/{id}")
    JsonNode get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        Caller caller = Caller.of(jwt, false);
        return jdbc.sql("SELECT CAST(body AS text) FROM events WHERE id = :id AND merchant_id = :merchant"
                        + caller.modeClause("livemode"))
                .param("id", id)
                .params(caller.params())
                .query(String.class)
                .optional()
                .map(mapper::readTree)
                .orElseThrow(() -> ApiException.notFound("event_not_found", "No such event"));
    }
}
