# 0005 — Transactional outbox with a polling relay

- **Status:** Accepted (Debezium planned for phase 9)
- **Date:** 2026-10-17

## Context
A service must change its database and publish an event atomically. Writing to Postgres and Kafka separately loses events if the process crashes between the two (the "dual write" problem).

## Decision
`libs/common-events` provides:

- **`OutboxWriter`:** inserts the event into the service's `outbox` table **in the same transaction** as the business change.
- **`OutboxRelay`:** a scheduled poller that selects unpublished rows (`FOR UPDATE SKIP LOCKED`, ordered by insertion), sends them to Kafka, waits for acknowledgement, and then sets `published_at`.
- **`InboxGuard`:** for consumers. It inserts the `event_id` into `processed_events` inside the consumer's transaction, so a redelivered event is skipped.

## Consequences
- Delivery is at-least-once: a crash after the send but before the `published_at` update re-sends the event. Consumers must therefore be idempotent, which is what `InboxGuard` is for.
- Polling adds about 0.5 s of latency, and running several instances is safe thanks to `SKIP LOCKED`.
- Every service owns its own `outbox` and `processed_events` tables in its Flyway migrations.
