-- The request log: one row per authenticated API call seen by the gateway, kept for 30 days.
-- Never holds bodies, headers or query strings (docs/adr/0011-request-log.md).
CREATE TABLE api_requests (
    id              TEXT PRIMARY KEY,               -- rl_<ulid>, so ids sort by time
    source_event_id TEXT        NOT NULL UNIQUE,    -- makes ingesting the same Kafka message twice a no-op
    merchant_id     TEXT        NOT NULL,
    livemode        BOOLEAN     NOT NULL,
    method          TEXT        NOT NULL,
    path            TEXT        NOT NULL,
    status          INT         NOT NULL,
    latency_ms      BIGINT      NOT NULL,
    actor           TEXT,
    actor_type      TEXT,
    idempotency_key TEXT,
    request_id      TEXT,
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX api_requests_merchant_idx ON api_requests (merchant_id, livemode, id DESC);
CREATE INDEX api_requests_created_idx ON api_requests (created_at);
