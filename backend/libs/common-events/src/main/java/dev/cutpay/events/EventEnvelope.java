package dev.cutpay.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * The common wrapper for every event published to Kafka (see docs/adr/0004-event-serialisation.md).
 *
 * @param livemode {@code null} for events that are not tied to test/live mode (e.g. merchant events)
 * @param version  schema version of {@code payload}; bump when the payload shape changes
 */
public record EventEnvelope<T>(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("aggregate_id") String aggregateId,
        @JsonProperty("merchant_id") String merchantId,
        Boolean livemode,
        int version,
        T payload) {
}
