# 0038 — Change data capture for the outbox: Debezium next to the polling relay

- **Status:** Accepted (as an option; polling stays the default)
- **Date:** 2026-10-22

## Context
ADR 0005 chose a polling relay: each service reads its own outbox table every half second and publishes unsent rows. It is simple and needs nothing but the service and the database. Its costs are latency (half the poll interval on average), a query against the table forever, and rows that must be marked and cleaned. The alternative is change data capture: read the rows from the database's write-ahead log as they are committed. The plan asked for the two to be compared.

## Decision
Both are supported, chosen per service with `cutpay.outbox.relay` (`polling` by default, `cdc`). In `cdc` mode the service only writes outbox rows (and deletes old ones); Debezium's Postgres connector with the outbox `EventRouter` publishes them, producing exactly the record the relay produces (same topic, key, value, and `event_id`, `event_type`, `traceparent` headers), so consumers and traces are unaffected. Setup, the safe cutover order and operations are in `infra/cdc/README.md`. Postgres now runs with `wal_level=logical` in the local stack.

## Measurements
Payments, same machine and load, event commit (`outbox.created_at`) to its record's timestamp on Kafka (`perf/relay-lag.mjs`):

| | one payment at a time | burst, 16 at a time |
|---|---|---|
| Polling relay (0.5 s) | p50 256 ms, p95 518 ms | p50 349 ms, p95 611 ms |
| Debezium, default `poll.interval.ms` (500) | p50 266 ms, p95 508 ms | |
| Debezium, `poll.interval.ms=20` | p50 16 ms, p95 29 ms | p50 48 ms, p95 84 ms |

Untuned, Debezium was no faster than polling: the connector has its own 500 ms poll. Tuned, the wait before an event is on Kafka fell by roughly 15 times. End to end, a merchant's webhook arrived after about 810-830 ms with either (`perf/outbox-latency.mjs`), because the consumers and the webhook worker's own one-second poll dominate. Faster publishing does not make the whole pipeline faster until those are changed too.

Resilience: with the Connect container stopped, 30 payments succeeded (checkout does not depend on it), the events were published after it returned, and reconciliation showed payments and ledger in agreement for all of them. The replication slot held 1.5 MB of log for those 30 payments while the connector was down.

## Consequences
- **Where it wins:** latency and load. No polling query; the commit-to-Kafka wait is tens of milliseconds; the service has less to do. The right choice when many services poll many tables, or when latency matters.
- **Where it costs:** another moving part (Kafka Connect), logical decoding on the database, and a new way to fail: a stopped connector makes Postgres keep log without limit, so slot lag needs an alert, and a slot nobody drops can fill a disk.
- **Cutover has an order.** The connector reads from the moment its slot exists. Turning the polling relay off first strands unpublished rows; this happened once during the comparison, and the rows were recovered by running the relay again. The right order (connector first, overlap with duplicates that consumers ignore, then switch) is written down.
- **Ordering and delivery guarantees are unchanged:** per key, at least once, deduplicated by `event_id`.
- Polling remains the default because it needs nothing else to run; the services are written so either can sit behind the same outbox table.

## Not done
- Only payments was run through Debezium; the other services would each need a connector (the template is parameterised by service name) and, in Kubernetes, Strimzi's `KafkaConnect` and `KafkaConnector` resources instead of the compose service.
- No alert for replication-slot lag in the rules yet.
