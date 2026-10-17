CREATE TABLE payment_intents (
    id               TEXT PRIMARY KEY,
    merchant_id      TEXT        NOT NULL,
    merchant_name    TEXT,
    livemode         BOOLEAN     NOT NULL,
    amount           BIGINT      NOT NULL CHECK (amount > 0),
    currency         VARCHAR(3)  NOT NULL,
    status           TEXT        NOT NULL,
    capture_method   TEXT        NOT NULL,
    description      TEXT,
    customer_email   TEXT,
    metadata         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    client_secret    TEXT        NOT NULL UNIQUE,
    return_url       TEXT,
    -- Succeeded plus still-pending refunds, so concurrent refunds cannot exceed the charge.
    amount_refunded  BIGINT      NOT NULL DEFAULT 0 CHECK (amount_refunded >= 0 AND amount_refunded <= amount),
    failure_code     TEXT,
    failure_message  TEXT,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_intents_merchant_idx ON payment_intents (merchant_id, id DESC);

CREATE TABLE payment_attempts (
    id                 TEXT PRIMARY KEY,
    payment_intent_id  TEXT        NOT NULL REFERENCES payment_intents (id),
    method_type        TEXT        NOT NULL,
    card_brand         TEXT,
    card_last4         VARCHAR(4),
    card_exp_month     INT,
    card_exp_year      INT,
    card_fingerprint   TEXT,
    acquirer_ref       TEXT,
    status             TEXT        NOT NULL,
    decline_code       TEXT,
    three_ds_status    TEXT,
    created_at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_attempts_intent_idx ON payment_attempts (payment_intent_id);
CREATE INDEX payment_attempts_acquirer_ref_idx ON payment_attempts (acquirer_ref);

CREATE TABLE refunds (
    id                 TEXT PRIMARY KEY,
    payment_intent_id  TEXT        NOT NULL REFERENCES payment_intents (id),
    merchant_id        TEXT        NOT NULL,
    amount             BIGINT      NOT NULL CHECK (amount > 0),
    currency           VARCHAR(3)  NOT NULL,
    reason             TEXT,
    status             TEXT        NOT NULL,
    acquirer_ref       TEXT,
    failure_code       TEXT,
    created_at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX refunds_intent_idx ON refunds (payment_intent_id);
CREATE INDEX refunds_merchant_idx ON refunds (merchant_id, id DESC);

CREATE TABLE idempotency_keys (
    merchant_id      TEXT        NOT NULL,
    key              TEXT        NOT NULL,
    request_hash     TEXT        NOT NULL,
    response_status  INT,
    response_body    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, key)
);

-- Transactional outbox + consumer inbox (docs/adr/0005-outbox-relay.md).
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
