-- A customer applying to pay a payment in instalments with one provider. The offer they chose is copied here, so what
-- they agreed to does not change if the provider quotes differently later.
CREATE TABLE bnpl_applications (
    id               TEXT PRIMARY KEY,
    payment_id       TEXT        NOT NULL,
    merchant_id      TEXT        NOT NULL,
    livemode         BOOLEAN     NOT NULL,
    provider         TEXT        NOT NULL,
    plan             TEXT        NOT NULL,
    -- created: sent to the provider; pending_review: the provider has not decided yet (a callback or a poll will say);
    -- approved / declined: the provider's answer; failed: the provider could not be reached; expired / canceled:
    -- abandoned; settled: the provider has paid Cutpay.
    status           TEXT        NOT NULL CHECK (status IN ('created', 'pending_review', 'approved', 'declined', 'failed',
                                                            'expired', 'canceled', 'settled')),
    provider_ref     TEXT,
    amount           BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    country          TEXT        NOT NULL,
    instalment_count INT         NOT NULL,
    instalments      JSONB       NOT NULL,
    interval_days    INT         NOT NULL,
    apr_percent      DOUBLE PRECISION NOT NULL,
    total_payable    BIGINT      NOT NULL,
    merchant_fee     BIGINT      NOT NULL,
    customer_email   TEXT,
    decline_reason   TEXT,
    -- How long the provider took to answer the application, whichever way it answered.
    decision_ms      BIGINT,
    expires_at       TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    decided_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX bnpl_applications_payment_idx ON bnpl_applications (payment_id, created_at DESC);
CREATE INDEX bnpl_applications_merchant_idx ON bnpl_applications (merchant_id, livemode, id DESC);
CREATE INDEX bnpl_applications_provider_ref_idx ON bnpl_applications (provider, provider_ref);
CREATE INDEX bnpl_applications_waiting_idx ON bnpl_applications (status) WHERE status IN ('created', 'pending_review');

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
