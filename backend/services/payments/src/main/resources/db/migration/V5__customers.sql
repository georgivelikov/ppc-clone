-- Customers, saved cards and setup intents (docs/plans/phase-05-billing.md).
-- A saved card is only a pointer to the vault: brand, last four digits and expiry for display, and the vault token.

CREATE TABLE customers (
    id                     TEXT PRIMARY KEY,
    merchant_id            TEXT        NOT NULL,
    livemode               BOOLEAN     NOT NULL,
    email                  TEXT,
    name                   TEXT,
    phone                  TEXT,
    description            TEXT,
    address                JSONB,
    metadata               JSONB       NOT NULL DEFAULT '{}'::jsonb,
    default_payment_method TEXT,
    deleted_at             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL
);
CREATE INDEX customers_merchant_idx ON customers (merchant_id, livemode, id DESC) WHERE deleted_at IS NULL;

CREATE TABLE payment_methods (
    id               TEXT PRIMARY KEY,
    merchant_id      TEXT        NOT NULL,
    livemode         BOOLEAN     NOT NULL,
    customer_id      TEXT        NOT NULL REFERENCES customers (id),
    type             TEXT        NOT NULL DEFAULT 'card',
    card_brand       TEXT,
    card_last4       VARCHAR(4),
    card_exp_month   INT,
    card_exp_year    INT,
    card_fingerprint TEXT,
    card_type        TEXT,
    card_region      TEXT,
    acquirer_token   TEXT        NOT NULL,
    detached_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_methods_customer_idx ON payment_methods (customer_id, id DESC) WHERE detached_at IS NULL;

CREATE TABLE setup_intents (
    id                TEXT PRIMARY KEY,
    merchant_id       TEXT        NOT NULL,
    merchant_name     TEXT,
    livemode          BOOLEAN     NOT NULL,
    customer_id       TEXT        NOT NULL REFERENCES customers (id),
    status            TEXT        NOT NULL,
    client_secret     TEXT        NOT NULL UNIQUE,
    payment_method_id TEXT,
    acquirer_token    TEXT,
    acquirer_ref      TEXT,
    description       TEXT,
    return_url        TEXT,
    failure_code      TEXT,
    failure_message   TEXT,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX setup_intents_acquirer_ref_idx ON setup_intents (acquirer_ref);
CREATE INDEX setup_intents_customer_idx ON setup_intents (customer_id, id DESC);

-- A payment can belong to a customer and be charged to a saved card, with or without the customer present.
ALTER TABLE payment_intents ADD COLUMN customer_id TEXT;
ALTER TABLE payment_intents ADD COLUMN payment_method_id TEXT;
ALTER TABLE payment_intents ADD COLUMN off_session BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE payment_intents ADD COLUMN setup_future_usage TEXT;
CREATE INDEX payment_intents_customer_idx ON payment_intents (customer_id, id DESC) WHERE customer_id IS NOT NULL;

-- The vault token an attempt used, so a card that should be saved can be saved once the payment succeeds.
ALTER TABLE payment_attempts ADD COLUMN acquirer_token TEXT;
