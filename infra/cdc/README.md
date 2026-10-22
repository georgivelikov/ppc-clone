# Change data capture for the outbox (Debezium)

Every service writes its events to an `outbox` table in the same transaction as the business change, and something has to move those rows to Kafka. By default each service does it itself, polling the table every 0.5 s (`OutboxRelay`). With change data capture, Debezium reads the rows from Postgres' write-ahead log instead and publishes them. The service only writes rows. See `docs/adr/0038-change-data-capture.md` for the comparison.

## Try it on one service (payments)
```
docker compose -f infra/docker-compose.yml up -d                       # Postgres now runs with wal_level=logical
docker compose -f infra/docker-compose.yml --profile cdc up -d connect # Kafka Connect with Debezium, REST on :8095

node infra/cdc/register.mjs payments                    # 1. register the connector while the polling relay still runs
CUTPAY_OUTBOX_RELAY=cdc node e2e/chaos/stack.mjs start payments   # 2. then switch the service to cdc (or set cutpay.outbox.relay=cdc)

node perf/relay-lag.mjs 150 1                           # commit-to-Kafka lag of the last events
node infra/cdc/register.mjs payments --remove           # back: remove connector and slot, then restart with the default
```

**The order matters.** The connector starts reading at the moment its replication slot is created (`snapshot.mode=no_data`). Rows written before that are not sent by it. So the connector goes in first, while the polling relay is still running (the overlap produces duplicates, which every consumer already ignores through `InboxGuard`), and only then is the polling relay switched off. Switching the relay off first strands whatever was unpublished. Going back is the reverse: start the polling relay, which sends anything still unpublished, then remove the connector.

## What is in the connector (`outbox-connector.template.json`)
- The `EventRouter` transform turns an outbox row into a Kafka record: the `topic` column is the destination, `event_key` the key, `payload` the value, and `event_id`, `event_type` and `traceparent` become headers - the same record the polling relay sends, so consumers cannot tell the difference.
- `poll.interval.ms=20`. The default is 500, which made Debezium no faster than the polling relay (p50 266 ms) before it was changed (p50 16 ms).
- `snapshot.mode=no_data`, `tombstones.on.delete=false`, a heartbeat every 10 s so the slot keeps advancing on a quiet database.
- Topics are not created on demand in this setup, so `register.mjs` creates the heartbeat topic.

## Operating it
- **A stopped connector holds the log.** Postgres cannot discard WAL a replication slot still needs. A connector that is down for a long time fills the disk. Watch `pg_replication_slots` (retained bytes) and alert on it; drop the slot if the connector is gone for good (`register.mjs --remove` does).
- **At-least-once, like polling.** After a failure Debezium may send a record again; consumers deduplicate on `event_id`.
- **Row clean-up.** Nothing marks rows published in this mode, so `OutboxCleanup` deletes rows older than `cutpay.outbox.retention` (one hour). Deleting is safe: the router ignores deletes.
- **One connector per database**, since each service has its own.
