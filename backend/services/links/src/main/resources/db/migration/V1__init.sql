CREATE TABLE payment_links (
    id                   TEXT PRIMARY KEY,
    merchant_id          TEXT        NOT NULL,
    merchant_name        TEXT,
    livemode             BOOLEAN     NOT NULL,
    slug                 TEXT        NOT NULL UNIQUE,
    type                 TEXT        NOT NULL CHECK (type IN ('fixed', 'customer_chooses', 'products')),
    amount               BIGINT      CHECK (amount > 0),
    currency             VARCHAR(3)  NOT NULL,
    min_amount           BIGINT      CHECK (min_amount > 0),
    max_amount           BIGINT      CHECK (max_amount > 0),
    title                TEXT        NOT NULL,
    description          TEXT,
    image_url            TEXT,
    collect              TEXT[]      NOT NULL DEFAULT '{}',
    quantity_adjustable  BOOLEAN     NOT NULL DEFAULT FALSE,
    max_uses             INT         CHECK (max_uses > 0),
    -- uses_count: payments that succeeded. reserved: customers who opened the link and may still pay.
    uses_count           INT         NOT NULL DEFAULT 0,
    amount_collected     BIGINT      NOT NULL DEFAULT 0,
    reserved             INT         NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    expires_at           TIMESTAMPTZ,
    active               BOOLEAN     NOT NULL DEFAULT TRUE,
    after_completion     TEXT        NOT NULL DEFAULT 'message' CHECK (after_completion IN ('message', 'redirect')),
    redirect_url         TEXT,
    success_message      TEXT,
    metadata             JSONB       NOT NULL DEFAULT '{}'::jsonb,
    qr_style             JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_links_merchant_idx ON payment_links (merchant_id, livemode, id DESC);

CREATE TABLE link_line_items (
    link_id       TEXT   NOT NULL REFERENCES payment_links (id),
    position      INT    NOT NULL,
    product_name  TEXT   NOT NULL,
    unit_amount   BIGINT NOT NULL CHECK (unit_amount > 0),
    quantity      INT    NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (link_id, position)
);

-- One row per customer who started paying. Holds one of the link's uses until the payment settles or
-- the reservation runs out.
CREATE TABLE link_reservations (
    id          TEXT PRIMARY KEY,
    link_id     TEXT        NOT NULL REFERENCES payment_links (id),
    payment_id  TEXT,
    amount      BIGINT      NOT NULL,
    status      TEXT        NOT NULL CHECK (status IN ('held', 'used', 'released')),
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX link_reservations_link_idx ON link_reservations (link_id, created_at DESC);
CREATE INDEX link_reservations_held_idx ON link_reservations (expires_at) WHERE status = 'held';
CREATE INDEX link_reservations_payment_idx ON link_reservations (payment_id);

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
