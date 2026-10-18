-- Credit a customer holds with a merchant: what a downgrade or a cancelled period credits beyond the next invoice.
-- The balance is the sum of the entries; it is kept in a row of its own so it can be locked while it is spent.
CREATE TABLE customer_credit (
    customer_id TEXT        NOT NULL,
    currency    CHAR(3)     NOT NULL,
    merchant_id TEXT        NOT NULL,
    livemode    BOOLEAN     NOT NULL,
    balance     BIGINT      NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (merchant_id, livemode, customer_id, currency)
);

CREATE TABLE customer_credit_entries (
    id            TEXT PRIMARY KEY,
    customer_id   TEXT        NOT NULL,
    currency      CHAR(3)     NOT NULL,
    merchant_id   TEXT        NOT NULL,
    livemode      BOOLEAN     NOT NULL,
    -- Positive when credit is added, negative when it is spent.
    amount        BIGINT      NOT NULL,
    balance_after BIGINT      NOT NULL,
    reason        TEXT        NOT NULL,
    invoice_id    TEXT,
    created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX customer_credit_entries_customer_idx ON customer_credit_entries (customer_id, created_at DESC, id DESC);

-- The part of the invoice that was paid from the customer's credit. `total` is what is left to pay by card.
ALTER TABLE invoices ADD COLUMN credit_applied BIGINT NOT NULL DEFAULT 0;
