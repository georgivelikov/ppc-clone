-- The read model (docs/adr/0029-reporting-read-model.md): flat tables built only from Kafka events. They hold no
-- truth of their own, so they can be dropped and rebuilt by replaying the topics. Each row remembers the time of
-- the last event that touched it, so events can arrive in any order and the result is the same.

CREATE TABLE fact_payments (
    payment_id    TEXT PRIMARY KEY,
    merchant_id   TEXT        NOT NULL,
    livemode      BOOLEAN     NOT NULL,
    currency      VARCHAR(3)  NOT NULL,
    amount        BIGINT      NOT NULL,
    status        TEXT        NOT NULL,
    method_type   TEXT,
    card_brand    TEXT,
    failure_code  TEXT,
    channel       TEXT,
    created_at    TIMESTAMPTZ NOT NULL,   -- the checkout was opened
    attempted_at  TIMESTAMPTZ,            -- the customer tried to pay
    succeeded_at  TIMESTAMPTZ,
    last_event_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX fact_payments_merchant_idx ON fact_payments (merchant_id, livemode, created_at);

CREATE TABLE fact_balance_transactions (
    transaction_id TEXT PRIMARY KEY,
    merchant_id    TEXT        NOT NULL,
    livemode       BOOLEAN     NOT NULL,
    currency       VARCHAR(3)  NOT NULL,
    type           TEXT        NOT NULL,
    source_id      TEXT,
    parent_id      TEXT,
    gross          BIGINT      NOT NULL,
    fee            BIGINT      NOT NULL,
    net            BIGINT      NOT NULL,
    description    TEXT,
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX fact_balance_merchant_idx ON fact_balance_transactions (merchant_id, livemode, created_at);

CREATE TABLE fact_payouts (
    payout_id     TEXT PRIMARY KEY,
    merchant_id   TEXT        NOT NULL,
    livemode      BOOLEAN     NOT NULL,
    currency      VARCHAR(3)  NOT NULL,
    amount        BIGINT      NOT NULL,
    fee           BIGINT      NOT NULL,
    status        TEXT        NOT NULL,
    kind          TEXT,
    arrival_date  TEXT,
    created_at    TIMESTAMPTZ NOT NULL,
    last_event_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX fact_payouts_merchant_idx ON fact_payouts (merchant_id, livemode, created_at);

CREATE TABLE fact_disputes (
    dispute_id    TEXT PRIMARY KEY,
    merchant_id   TEXT        NOT NULL,
    livemode      BOOLEAN     NOT NULL,
    payment_id    TEXT        NOT NULL,
    currency      VARCHAR(3)  NOT NULL,
    amount        BIGINT      NOT NULL,
    fee           BIGINT      NOT NULL,
    status        TEXT        NOT NULL,
    reason        TEXT,
    created_at    TIMESTAMPTZ NOT NULL,
    closed_at     TIMESTAMPTZ,
    last_event_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX fact_disputes_merchant_idx ON fact_disputes (merchant_id, livemode, created_at);

-- Report runs: asked for, built by a job, kept for download.
CREATE TABLE report_runs (
    id                 TEXT PRIMARY KEY,
    merchant_id        TEXT        NOT NULL,
    livemode           BOOLEAN     NOT NULL,
    type               TEXT        NOT NULL,
    format             TEXT        NOT NULL CHECK (format IN ('csv', 'xlsx')),
    interval_start     DATE        NOT NULL,
    interval_end       DATE        NOT NULL,
    columns            TEXT,                 -- comma-separated subset; null means all
    status             TEXT        NOT NULL CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    error              TEXT,
    row_count          INT,
    requested_by_email TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ
);
CREATE INDEX report_runs_merchant_idx ON report_runs (merchant_id, livemode, created_at DESC);
CREATE INDEX report_runs_queue_idx ON report_runs (created_at) WHERE status = 'queued';

CREATE TABLE report_files (
    run_id       TEXT PRIMARY KEY REFERENCES report_runs (id),
    filename     TEXT  NOT NULL,
    content_type TEXT  NOT NULL,
    content      BYTEA NOT NULL
);

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
