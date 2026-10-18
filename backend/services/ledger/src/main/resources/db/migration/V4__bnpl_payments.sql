-- What the ledger needs to remember about a payment financed in instalments: the fee its provider keeps (refunds give
-- back the same share of it), how much has been refunded, and whether the provider has paid yet.
CREATE TABLE bnpl_payments (
    payment_id     TEXT PRIMARY KEY,
    merchant_id    TEXT        NOT NULL,
    livemode       BOOLEAN     NOT NULL,
    currency       CHAR(3)     NOT NULL,
    gross          BIGINT      NOT NULL,
    fee            BIGINT      NOT NULL,
    -- Refunded to the customer, and the part of it that comes out of what the provider owes (the rest is its fee, returned).
    refunded_gross BIGINT      NOT NULL DEFAULT 0 CHECK (refunded_gross <= gross),
    refunded_net   BIGINT      NOT NULL DEFAULT 0,
    settled_at     TIMESTAMPTZ,
    settled_amount BIGINT
);
