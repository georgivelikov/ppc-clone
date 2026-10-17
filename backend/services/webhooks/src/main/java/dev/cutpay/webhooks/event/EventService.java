package dev.cutpay.webhooks.event;

import dev.cutpay.events.InboxGuard;
import dev.cutpay.webhooks.config.Sql;
import dev.cutpay.webhooks.config.WebhookProperties;
import dev.cutpay.webhooks.event.EventMapper.Mapped;
import dev.cutpay.web.Ids;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates the public events merchants see, and queues one delivery per subscribed endpoint. Both happen in
 * the transaction that records the internal event as processed, so a redelivered Kafka message changes nothing
 * and a crash cannot leave an event without its deliveries.
 */
@Service
public class EventService {

    private final JdbcClient jdbc;
    private final InboxGuard inbox;
    private final Clock clock;
    private final JsonMapper mapper;
    private final WebhookProperties properties;

    public EventService(JdbcClient jdbc, InboxGuard inbox, Clock clock, JsonMapper mapper,
                        WebhookProperties properties) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.clock = clock;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** Handles one internal event from Kafka. Types merchants cannot see are ignored. */
    @Transactional
    public void ingest(String sourceEventId, String sourceType, String merchantId, boolean livemode,
                       JsonNode payload) {
        if (!inbox.firstTime(sourceEventId)) {
            return;
        }
        Optional<Mapped> mapped = EventMapper.map(sourceType, payload);
        if (mapped.isEmpty()) {
            return;
        }
        // The public event keeps the id of the internal one, so ids stay stable across redeliveries.
        String eventId = insertEvent(sourceEventId, merchantId, livemode, mapped.get().type(),
                mapped.get().object());
        for (Target endpoint : subscribedEndpoints(merchantId, livemode, mapped.get().type())) {
            queueDelivery(eventId, endpoint.id(), merchantId, livemode);
        }
    }

    /** A synthetic event sent to one endpoint only, so a merchant can check their receiver works. */
    @Transactional
    public String sendTestEvent(String endpointId, String merchantId, boolean livemode, String url) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", endpointId);
        object.put("object", "webhook_endpoint");
        object.put("url", url);
        String eventId = insertEvent("evt_" + java.util.UUID.randomUUID(), merchantId, livemode, EventTypes.TEST,
                object);
        return queueDelivery(eventId, endpointId, merchantId, livemode);
    }

    private String insertEvent(String eventId, String merchantId, boolean livemode, String type,
                               Map<String, Object> object) {
        Instant now = clock.instant();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", eventId);
        body.put("object", "event");
        body.put("type", type);
        body.put("api_version", properties.apiVersion());
        body.put("livemode", livemode);
        body.put("created_at", now.toString());
        body.put("data", Map.of("object", object));
        jdbc.sql("""
                        INSERT INTO events (id, merchant_id, livemode, type, api_version, body, created_at)
                        VALUES (:id, :merchant, :livemode, :type, :version, CAST(:body AS jsonb), :createdAt)
                        """)
                .param("id", eventId)
                .param("merchant", merchantId)
                .param("livemode", livemode)
                .param("type", type)
                .param("version", properties.apiVersion())
                .param("body", mapper.writeValueAsString(body))
                .param("createdAt", Sql.ts(now))
                .update();
        return eventId;
    }

    private record Target(String id) {
    }

    private List<Target> subscribedEndpoints(String merchantId, boolean livemode, String type) {
        record Row(String id, List<String> events) {
        }
        return jdbc.sql("""
                        SELECT id, enabled_events FROM webhook_endpoints
                         WHERE merchant_id = :merchant AND livemode = :livemode AND status = 'enabled'
                        """)
                .param("merchant", merchantId)
                .param("livemode", livemode)
                .query((rs, n) -> new Row(rs.getString("id"), textArray(rs.getArray("enabled_events"))))
                .list().stream()
                .filter(row -> EventTypes.matches(row.events(), type))
                .map(row -> new Target(row.id()))
                .toList();
    }

    private String queueDelivery(String eventId, String endpointId, String merchantId, boolean livemode) {
        String id = Ids.generate("whd");
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO webhook_deliveries (id, event_id, endpoint_id, merchant_id, livemode, status,
                                                        attempts, next_attempt_at, created_at)
                        VALUES (:id, :event, :endpoint, :merchant, :livemode, 'pending', 0, :now, :now)
                        ON CONFLICT (event_id, endpoint_id) DO NOTHING
                        """)
                .param("id", id)
                .param("event", eventId)
                .param("endpoint", endpointId)
                .param("merchant", merchantId)
                .param("livemode", livemode)
                .param("now", Sql.ts(now))
                .update();
        return id;
    }

    public static List<String> textArray(java.sql.Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }
}
