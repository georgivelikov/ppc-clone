-- Who to write to at each business, kept from the identity service's team events.
CREATE TABLE merchant_contacts (
    merchant_id TEXT        NOT NULL,
    user_id     TEXT        NOT NULL,
    email       TEXT        NOT NULL,
    full_name   TEXT,
    role        TEXT        NOT NULL,
    locale      VARCHAR(5)  NOT NULL DEFAULT 'en',
    PRIMARY KEY (merchant_id, user_id)
);

CREATE TABLE merchants (
    merchant_id TEXT PRIMARY KEY,
    name        TEXT NOT NULL
);

-- One row per email: what was asked for, to whom, and what became of it.
CREATE TABLE notification_log (
    id           TEXT PRIMARY KEY,
    event_id     TEXT        NOT NULL,
    merchant_id  TEXT,
    recipient    TEXT        NOT NULL,
    template     TEXT        NOT NULL,
    locale       VARCHAR(5)  NOT NULL,
    subject      TEXT,
    body         TEXT,
    status       TEXT        NOT NULL CHECK (status IN ('queued', 'sent', 'failed', 'dead', 'skipped')),
    attempts     INT         NOT NULL DEFAULT 0,
    last_error   TEXT,
    next_attempt TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ,
    UNIQUE (event_id, recipient)
);
CREATE INDEX notification_log_merchant_idx ON notification_log (merchant_id, created_at DESC);
CREATE INDEX notification_log_retry_idx ON notification_log (next_attempt) WHERE status IN ('queued', 'failed');

-- Only exceptions are stored: an email is on unless the person switched it off (or it is off by default).
CREATE TABLE notification_preferences (
    merchant_id TEXT    NOT NULL,
    email       TEXT    NOT NULL,
    template    TEXT    NOT NULL,
    enabled     BOOLEAN NOT NULL,
    PRIMARY KEY (merchant_id, email, template)
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
