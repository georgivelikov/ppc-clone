# 0004 — JSON events with a shared envelope (Avro deferred)

- **Status:** Accepted (revisit in phase 2)
- **Date:** 2026-10-17

## Context
The plan recommended Avro with a Schema Registry. With Spring Boot 4 / Jackson 3 / Gradle 9, the Avro code-generation plugins and the Confluent serializer add tooling risk before we have even one consumer.

## Decision
Phase 0 publishes **JSON** events as plain strings, with a common envelope:

```json
{ "event_id": "evt_…", "event_type": "MerchantCreated", "occurred_at": "…",
  "aggregate_id": "mer_…", "merchant_id": "mer_…", "livemode": null, "version": 1, "payload": { … } }
```

- The Kafka record key is the aggregate id, which keeps ordering per aggregate.
- `event_type` and `event_id` are also sent as Kafka headers, so consumers can route without parsing the body.
- The Schema Registry container is left out of `docker-compose` until it is needed.

## Consequences
- It is easy to inspect messages in Kafka UI.
- There is no compile-time schema compatibility check. Phase 2, when the first consumers appear, re-evaluates Avro or JSON Schema with the registry.
