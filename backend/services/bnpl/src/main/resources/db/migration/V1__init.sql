-- What a merchant has switched on, and in which order providers are preferred. A provider without a row is on, in
-- the catalogue order.
CREATE TABLE merchant_bnpl_settings (
    merchant_id   TEXT        NOT NULL,
    livemode      BOOLEAN     NOT NULL,
    provider_code TEXT        NOT NULL,
    enabled       BOOLEAN     NOT NULL,
    priority      INT         NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (merchant_id, livemode, provider_code)
);

CREATE TABLE merchant_routing (
    merchant_id     TEXT        NOT NULL,
    livemode        BOOLEAN     NOT NULL,
    routing_mode    TEXT        NOT NULL CHECK (routing_mode IN ('customer_choice', 'cheapest', 'fastest', 'best_approval', 'weighted')),
    -- Used by the weighted mode: how much cost, speed and approval rate count, as relative whole numbers.
    weight_cost     INT         NOT NULL DEFAULT 1 CHECK (weight_cost >= 0),
    weight_speed    INT         NOT NULL DEFAULT 1 CHECK (weight_speed >= 0),
    weight_approval INT         NOT NULL DEFAULT 1 CHECK (weight_approval >= 0),
    updated_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (merchant_id, livemode)
);

-- Why a provider was chosen for a payment: kept so the dashboard can explain it.
CREATE TABLE routing_decisions (
    id              TEXT PRIMARY KEY,
    payment_id      TEXT        NOT NULL,
    merchant_id     TEXT        NOT NULL,
    livemode        BOOLEAN     NOT NULL,
    strategy        TEXT        NOT NULL,
    amount          BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,
    country         TEXT        NOT NULL,
    chosen_provider TEXT,
    reason          TEXT        NOT NULL,
    -- The ranked candidates with their offers, and the providers that were left out and why.
    detail          JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX routing_decisions_payment_idx ON routing_decisions (payment_id, created_at DESC);
CREATE INDEX routing_decisions_merchant_idx ON routing_decisions (merchant_id, livemode, created_at DESC);
