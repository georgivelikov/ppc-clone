-- Webhooks: endpoints, public events and their deliveries (docs/adr/0010-webhook-delivery.md).

CREATE TABLE webhook_endpoints (
    id             TEXT PRIMARY KEY,                -- we_...
    merchant_id    TEXT        NOT NULL,
    livemode       BOOLEAN     NOT NULL,
    url            TEXT        NOT NULL,
    description    TEXT,
    secret         TEXT        NOT NULL,            -- whsec_..., shown once
    enabled_events TEXT[]      NOT NULL,            -- exact types, 'prefix.*' or '*'
    status         TEXT        NOT NULL CHECK (status IN ('enabled', 'disabled', 'deleted')),
    disabled_reason TEXT,
    failing_since  TIMESTAMPTZ,                     -- start of the current run of failures
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX webhook_endpoints_merchant_idx ON webhook_endpoints (merchant_id, livemode, id DESC);

-- The public event object. The id is the id of the internal Kafka event it was made from, which
-- also makes ingesting the same event twice a no-op.
CREATE TABLE events (
    id          TEXT PRIMARY KEY,                   -- evt_...
    merchant_id TEXT        NOT NULL,
    livemode    BOOLEAN     NOT NULL,
    type        TEXT        NOT NULL,
    api_version TEXT        NOT NULL,
    body        JSONB       NOT NULL,               -- the exact object sent to endpoints
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX events_merchant_idx ON events (merchant_id, livemode, id DESC);

-- One row per (event, endpoint): its current state. Every attempt is kept in delivery_attempts.
CREATE TABLE webhook_deliveries (
    id              TEXT PRIMARY KEY,               -- whd_...
    event_id        TEXT        NOT NULL REFERENCES events (id),
    endpoint_id     TEXT        NOT NULL REFERENCES webhook_endpoints (id),
    merchant_id     TEXT        NOT NULL,
    livemode        BOOLEAN     NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,                    -- NULL once finished
    last_response_code INT,
    created_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (event_id, endpoint_id)
);
CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at) WHERE status = 'pending';
CREATE INDEX webhook_deliveries_merchant_idx ON webhook_deliveries (merchant_id, livemode, id DESC);
CREATE INDEX webhook_deliveries_endpoint_idx ON webhook_deliveries (endpoint_id, id DESC);

CREATE TABLE delivery_attempts (
    id            TEXT PRIMARY KEY,                 -- wha_...
    delivery_id   TEXT        NOT NULL REFERENCES webhook_deliveries (id),
    attempt       INT         NOT NULL,
    response_code INT,                              -- NULL when the request never got an answer
    response_body_snippet TEXT,
    error         TEXT,
    duration_ms   BIGINT      NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX delivery_attempts_delivery_idx ON delivery_attempts (delivery_id, attempt);

-- Consumer inbox, and the scheduled-job lock table (docs/adr/0005-outbox-relay.md).
CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_id     TEXT        NOT NULL UNIQUE,
    topic        TEXT        NOT NULL,
    event_key    TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id     TEXT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
