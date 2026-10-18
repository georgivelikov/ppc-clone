-- Billing: products and prices, subscriptions, invoices and the retries for unpaid ones
-- (docs/plans/phase-05-billing.md). Customers and saved cards live in the payments service.

CREATE TABLE products (
    id          TEXT PRIMARY KEY,
    merchant_id TEXT        NOT NULL,
    livemode    BOOLEAN     NOT NULL,
    name        TEXT        NOT NULL,
    description TEXT,
    image_url   TEXT,
    active      BOOLEAN     NOT NULL DEFAULT true,
    metadata    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX products_merchant_idx ON products (merchant_id, livemode, id DESC);

-- A price never changes once created, so a subscription always knows what it agreed to: archive it and add a new one.
CREATE TABLE prices (
    id             TEXT PRIMARY KEY,
    merchant_id    TEXT        NOT NULL,
    livemode       BOOLEAN     NOT NULL,
    product_id     TEXT        NOT NULL REFERENCES products (id),
    unit_amount    BIGINT      NOT NULL CHECK (unit_amount > 0),
    currency       CHAR(3)     NOT NULL,
    type           TEXT        NOT NULL CHECK (type IN ('one_time', 'recurring')),
    billing_interval TEXT      CHECK (billing_interval IN ('day', 'week', 'month', 'year')),
    interval_count INT         NOT NULL DEFAULT 1 CHECK (interval_count BETWEEN 1 AND 365),
    trial_days     INT         NOT NULL DEFAULT 0 CHECK (trial_days BETWEEN 0 AND 730),
    nickname       TEXT,
    active         BOOLEAN     NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT recurring_has_interval CHECK ((type = 'recurring') = (billing_interval IS NOT NULL))
);
CREATE INDEX prices_product_idx ON prices (product_id, id DESC);

CREATE TABLE subscriptions (
    id                    TEXT PRIMARY KEY,
    merchant_id           TEXT        NOT NULL,
    merchant_name         TEXT,
    livemode              BOOLEAN     NOT NULL,
    customer_id           TEXT        NOT NULL,
    customer_email        TEXT,
    customer_name         TEXT,
    status                TEXT        NOT NULL CHECK (status IN ('trialing', 'active', 'past_due', 'canceled', 'unpaid', 'incomplete')),
    currency              CHAR(3)     NOT NULL,
    -- All items of a subscription are billed together, so they share one interval.
    billing_interval      TEXT        NOT NULL CHECK (billing_interval IN ('day', 'week', 'month', 'year')),
    interval_count        INT         NOT NULL CHECK (interval_count BETWEEN 1 AND 365),
    collection_method     TEXT        NOT NULL CHECK (collection_method IN ('charge_automatically', 'send_invoice')),
    default_payment_method TEXT,
    -- Period k runs from anchor + k intervals to anchor + (k + 1) intervals, so a 31st never drifts to the 28th.
    billing_cycle_anchor  TIMESTAMPTZ NOT NULL,
    period_index          INT         NOT NULL DEFAULT 0,
    current_period_start  TIMESTAMPTZ NOT NULL,
    current_period_end    TIMESTAMPTZ NOT NULL,
    trial_end             TIMESTAMPTZ,
    cancel_at_period_end  BOOLEAN     NOT NULL DEFAULT false,
    collection_paused     BOOLEAN     NOT NULL DEFAULT false,
    canceled_at           TIMESTAMPTZ,
    ended_at              TIMESTAMPTZ,
    tax_rate_bps          INT         NOT NULL DEFAULT 0 CHECK (tax_rate_bps BETWEEN 0 AND 10000),
    latest_invoice_id     TEXT,
    metadata              JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);
CREATE INDEX subscriptions_merchant_idx ON subscriptions (merchant_id, livemode, id DESC);
CREATE INDEX subscriptions_customer_idx ON subscriptions (customer_id, id DESC);
CREATE INDEX subscriptions_due_idx ON subscriptions (current_period_end) WHERE status IN ('active', 'trialing');

CREATE TABLE subscription_items (
    id              TEXT PRIMARY KEY,
    subscription_id TEXT   NOT NULL REFERENCES subscriptions (id),
    price_id        TEXT   NOT NULL REFERENCES prices (id),
    quantity        INT    NOT NULL CHECK (quantity BETWEEN 1 AND 100000)
);
CREATE INDEX subscription_items_subscription_idx ON subscription_items (subscription_id);

CREATE TABLE invoices (
    id                 TEXT PRIMARY KEY,
    merchant_id        TEXT        NOT NULL,
    merchant_name      TEXT,
    livemode           BOOLEAN     NOT NULL,
    subscription_id    TEXT        REFERENCES subscriptions (id),
    customer_id        TEXT        NOT NULL,
    customer_email     TEXT,
    customer_name      TEXT,
    number             TEXT,
    status             TEXT        NOT NULL CHECK (status IN ('draft', 'open', 'paid', 'void', 'uncollectible')),
    billing_reason     TEXT        NOT NULL,
    collection_method  TEXT        NOT NULL CHECK (collection_method IN ('charge_automatically', 'send_invoice')),
    currency           CHAR(3)     NOT NULL,
    subtotal           BIGINT      NOT NULL DEFAULT 0,
    tax                BIGINT      NOT NULL DEFAULT 0,
    total              BIGINT      NOT NULL DEFAULT 0,
    amount_paid        BIGINT      NOT NULL DEFAULT 0,
    tax_rate_bps       INT         NOT NULL DEFAULT 0,
    description        TEXT,
    default_payment_method TEXT,
    payment_intent_id  TEXT,
    last_failed_payment_id TEXT,
    failure_code       TEXT,
    hosted_token       TEXT        NOT NULL UNIQUE,
    attempt_count      INT         NOT NULL DEFAULT 0,
    first_failed_at    TIMESTAMPTZ,
    period_start       TIMESTAMPTZ,
    period_end         TIMESTAMPTZ,
    due_date           TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,
    finalized_at       TIMESTAMPTZ,
    paid_at            TIMESTAMPTZ,
    voided_at          TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT invoice_number_unique UNIQUE (merchant_id, livemode, number)
);
CREATE INDEX invoices_merchant_idx ON invoices (merchant_id, livemode, id DESC);
CREATE INDEX invoices_customer_idx ON invoices (customer_id, id DESC);
CREATE INDEX invoices_subscription_idx ON invoices (subscription_id, id DESC);
CREATE INDEX invoices_payment_idx ON invoices (payment_intent_id);

CREATE TABLE invoice_lines (
    id           TEXT PRIMARY KEY,
    invoice_id   TEXT        NOT NULL REFERENCES invoices (id),
    description  TEXT        NOT NULL,
    unit_amount  BIGINT      NOT NULL,
    quantity     INT         NOT NULL,
    amount       BIGINT      NOT NULL,
    price_id     TEXT,
    proration    BOOLEAN     NOT NULL DEFAULT false,
    period_start TIMESTAMPTZ,
    period_end   TIMESTAMPTZ,
    position     INT         NOT NULL
);
CREATE INDEX invoice_lines_invoice_idx ON invoice_lines (invoice_id, position);

-- Lines waiting for the next invoice of the subscription, for example the proration of a plan change.
CREATE TABLE pending_invoice_items (
    id              TEXT PRIMARY KEY,
    merchant_id     TEXT        NOT NULL,
    livemode        BOOLEAN     NOT NULL,
    subscription_id TEXT        NOT NULL REFERENCES subscriptions (id),
    description     TEXT        NOT NULL,
    unit_amount     BIGINT      NOT NULL,
    quantity        INT         NOT NULL,
    amount          BIGINT      NOT NULL,
    price_id        TEXT,
    proration       BOOLEAN     NOT NULL DEFAULT true,
    period_start    TIMESTAMPTZ,
    period_end      TIMESTAMPTZ,
    invoice_id      TEXT        REFERENCES invoices (id),
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX pending_items_subscription_idx ON pending_invoice_items (subscription_id) WHERE invoice_id IS NULL;

CREATE TABLE invoice_counters (
    merchant_id TEXT    NOT NULL,
    livemode    BOOLEAN NOT NULL,
    next_number BIGINT  NOT NULL,
    PRIMARY KEY (merchant_id, livemode)
);

-- A retry of a failed invoice payment: scheduled when the payment fails, done by the job once it is due.
CREATE TABLE dunning_attempts (
    id           TEXT PRIMARY KEY,
    invoice_id   TEXT        NOT NULL REFERENCES invoices (id),
    attempt_no   INT         NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    executed_at  TIMESTAMPTZ,
    result       TEXT,
    UNIQUE (invoice_id, attempt_no)
);
CREATE INDEX dunning_due_idx ON dunning_attempts (scheduled_at) WHERE executed_at IS NULL;

CREATE TABLE billing_settings (
    merchant_id  TEXT PRIMARY KEY,
    -- Days after the first failed payment on which the invoice is tried again.
    retry_days   INT[]       NOT NULL DEFAULT '{1,3,5,7}',
    -- What happens after the last retry fails: cancel the subscription, or leave it as unpaid.
    final_action TEXT        NOT NULL DEFAULT 'cancel' CHECK (final_action IN ('cancel', 'unpaid')),
    updated_at   TIMESTAMPTZ NOT NULL
);

-- Every email the platform would send. Real delivery arrives in phase 8; until then this is the record.
CREATE TABLE notifications (
    id          TEXT PRIMARY KEY,
    merchant_id TEXT        NOT NULL,
    livemode    BOOLEAN     NOT NULL,
    kind        TEXT        NOT NULL,
    recipient   TEXT,
    subject     TEXT        NOT NULL,
    body        TEXT        NOT NULL,
    related_id  TEXT,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX notifications_related_idx ON notifications (related_id, created_at);
CREATE INDEX notifications_merchant_idx ON notifications (merchant_id, livemode, created_at DESC);

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
