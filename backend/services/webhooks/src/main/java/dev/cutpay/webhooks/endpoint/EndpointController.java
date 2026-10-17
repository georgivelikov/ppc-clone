package dev.cutpay.webhooks.endpoint;

import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import dev.cutpay.webhooks.config.Caller;
import dev.cutpay.webhooks.config.Sql;
import dev.cutpay.webhooks.delivery.UrlPolicy;
import dev.cutpay.webhooks.event.EventService;
import dev.cutpay.webhooks.event.EventTypes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a merchant wants events sent. The signing secret is returned once, on creation; after that only
 * the endpoint (and the signatures on what it receives) prove it.
 */
@RestController
class EndpointController {

    private static final int MAX_ENDPOINTS = 20;
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> SETTABLE_STATUS = Set.of("enabled", "disabled");

    record CreateRequest(@NotBlank @Size(max = 2000) String url, List<String> enabledEvents,
                         @Size(max = 200) String description) {
    }

    record UpdateRequest(@Size(max = 2000) String url, List<String> enabledEvents,
                         @Size(max = 200) String description, String status) {
    }

    record EndpointView(String id, String object, String url, String description, List<String> enabledEvents,
                        String status, String disabledReason, boolean livemode, Instant createdAt, String secret) {
    }

    record EndpointList(String object, List<EndpointView> data) {
    }

    record Deleted(String id, String object, boolean deleted) {
    }

    record TestResult(String deliveryId, String endpoint) {
    }

    private static final String SELECT = """
            SELECT id, url, description, enabled_events, status, disabled_reason, livemode, created_at
              FROM webhook_endpoints
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final EventService events;

    EndpointController(JdbcClient jdbc, Clock clock, EventService events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    @PostMapping("/v1/webhook_endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    EndpointView create(@AuthenticationPrincipal Jwt jwt,
                        @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                        @Valid @RequestBody CreateRequest request) {
        Caller caller = Caller.of(jwt, requestedLivemode);
        caller.requireWrite();
        boolean livemode = caller.livemode();
        String url = validUrl(request.url(), livemode);
        List<String> subscriptions = validEvents(request.enabledEvents(), List.of("*"));
        long existing = jdbc.sql("""
                        SELECT count(*) FROM webhook_endpoints
                         WHERE merchant_id = :merchant AND livemode = :livemode AND status <> 'deleted'
                        """)
                .param("merchant", caller.merchantId()).param("livemode", livemode)
                .query(Long.class).single();
        if (existing >= MAX_ENDPOINTS) {
            throw ApiException.invalidRequest("too_many_endpoints",
                    "You can have at most " + MAX_ENDPOINTS + " endpoints per mode", null);
        }
        String id = Ids.generate("we");
        String secret = "whsec_" + randomBase62(32);
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO webhook_endpoints (id, merchant_id, livemode, url, description, secret,
                                                       enabled_events, status, created_at)
                        VALUES (:id, :merchant, :livemode, :url, :description, :secret,
                                CAST(:events AS text[]), 'enabled', :createdAt)
                        """)
                .param("id", id).param("merchant", caller.merchantId()).param("livemode", livemode)
                .param("url", url).param("description", request.description()).param("secret", secret)
                .param("events", pgArray(subscriptions)).param("createdAt", Sql.ts(now))
                .update();
        return find(caller, id, secret);
    }

    @GetMapping("/v1/webhook_endpoints")
    EndpointList list(@AuthenticationPrincipal Jwt jwt,
                      @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        Caller caller = Caller.of(jwt, requestedLivemode);
        List<EndpointView> data = jdbc.sql(SELECT + """
                         WHERE merchant_id = :merchant AND livemode = :livemode AND status <> 'deleted'
                         ORDER BY id DESC
                        """)
                .param("merchant", caller.merchantId()).param("livemode", caller.livemode())
                .query((rs, n) -> view(rs, null)).list();
        return new EndpointList("list", data);
    }

    @GetMapping("/v1/webhook_endpoints/{id}")
    EndpointView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return find(Caller.of(jwt, false), id, null);
    }

    @PatchMapping("/v1/webhook_endpoints/{id}")
    @Transactional
    EndpointView update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                        @Valid @RequestBody UpdateRequest request) {
        Caller caller = Caller.of(jwt, false);
        caller.requireWrite();
        EndpointView current = find(caller, id, null);
        String url = request.url() == null ? current.url() : validUrl(request.url(), current.livemode());
        List<String> subscriptions = validEvents(request.enabledEvents(), current.enabledEvents());
        String description = request.description() == null ? current.description() : request.description();
        String status = current.status();
        if (request.status() != null) {
            if (!SETTABLE_STATUS.contains(request.status())) {
                throw ApiException.invalidRequest("invalid_status", "status must be enabled or disabled", "status");
            }
            status = request.status();
        }
        boolean reenabled = status.equals("enabled") && !current.status().equals("enabled");
        jdbc.sql("""
                        UPDATE webhook_endpoints
                           SET url = :url, description = :description, enabled_events = CAST(:events AS text[]),
                               status = :status,
                               disabled_reason = CASE WHEN :status = 'disabled' THEN COALESCE(:reason, disabled_reason)
                                                      ELSE NULL END,
                               failing_since = CASE WHEN :reenabled THEN NULL ELSE failing_since END
                         WHERE id = :id AND merchant_id = :merchant
                        """)
                .param("url", url).param("description", description).param("events", pgArray(subscriptions))
                .param("status", status)
                .param("reason", status.equals("disabled") && !current.status().equals("disabled")
                        ? "Disabled by you" : null)
                .param("reenabled", reenabled)
                .param("id", id).param("merchant", caller.merchantId())
                .update();
        return find(caller, id, null);
    }

    /** Removing keeps the row: past deliveries still point at it. */
    @DeleteMapping("/v1/webhook_endpoints/{id}")
    @Transactional
    Deleted delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        Caller caller = Caller.of(jwt, false);
        caller.requireWrite();
        find(caller, id, null);
        jdbc.sql("UPDATE webhook_endpoints SET status = 'deleted' WHERE id = :id AND merchant_id = :merchant")
                .param("id", id).param("merchant", caller.merchantId()).update();
        return new Deleted(id, "webhook_endpoint", true);
    }

    /** Queues a {@code webhook_endpoint.test} event for this endpoint alone. */
    @PostMapping("/v1/webhook_endpoints/{id}/test")
    @ResponseStatus(HttpStatus.CREATED)
    TestResult test(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        Caller caller = Caller.of(jwt, false);
        caller.requireWrite();
        EndpointView endpoint = find(caller, id, null);
        if (!endpoint.status().equals("enabled")) {
            throw ApiException.invalidRequest("endpoint_disabled", "Enable the endpoint before sending a test event",
                    null);
        }
        String deliveryId = events.sendTestEvent(id, caller.merchantId(), endpoint.livemode(), endpoint.url());
        return new TestResult(deliveryId, id);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private EndpointView find(Caller caller, String id, String secret) {
        return jdbc.sql(SELECT + " WHERE id = :id AND merchant_id = :merchant AND status <> 'deleted'"
                        + caller.modeClause("livemode"))
                .param("id", id)
                .params(caller.params())
                .query((rs, n) -> view(rs, secret))
                .optional()
                .orElseThrow(() -> ApiException.notFound("webhook_endpoint_not_found", "No such webhook endpoint"));
    }

    private static EndpointView view(ResultSet rs, String secret) throws SQLException {
        return new EndpointView(rs.getString("id"), "webhook_endpoint", rs.getString("url"),
                rs.getString("description"), EventService.textArray(rs.getArray("enabled_events")),
                rs.getString("status"), rs.getString("disabled_reason"), rs.getBoolean("livemode"),
                Sql.instant(rs, "created_at"), secret);
    }

    private static String validUrl(String url, boolean livemode) {
        String trimmed = url.trim();
        UrlPolicy.problemWith(trimmed, livemode)
                .ifPresent(problem -> {
                    throw ApiException.invalidRequest("invalid_url", problem, "url");
                });
        return trimmed;
    }

    private static List<String> validEvents(List<String> requested, List<String> fallback) {
        if (requested == null) {
            return fallback;
        }
        if (requested.isEmpty()) {
            throw ApiException.invalidRequest("events_required", "Choose at least one event type", "enabled_events");
        }
        for (String entry : requested) {
            if (!EventTypes.isValidSubscription(entry)) {
                throw ApiException.invalidRequest("invalid_event_type", "Unknown event type: " + entry,
                        "enabled_events");
            }
        }
        return requested.stream().distinct().toList();
    }

    /** Postgres array literal: {a,b}. Event types contain only letters, dots, underscores and a star. */
    private static String pgArray(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private static String randomBase62(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return out.toString();
    }
}
