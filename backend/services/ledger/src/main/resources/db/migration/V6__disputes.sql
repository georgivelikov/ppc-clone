-- Money held while a dispute is decided has its own account, so it is neither the merchant's nor the customer's yet.
ALTER TABLE accounts DROP CONSTRAINT accounts_type_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_type_check
    CHECK (type IN ('merchant_pending', 'merchant_available', 'merchant_reserve', 'platform_fees', 'platform_cash',
                    'payouts_in_transit', 'customer_funds', 'bnpl_receivable', 'platform_fx', 'disputes_held'));

-- What the ledger took when a dispute opened, so that closing it gives back exactly that.
CREATE TABLE ledger_disputes (
    dispute_id  TEXT PRIMARY KEY,
    merchant_id TEXT        NOT NULL,
    livemode    BOOLEAN     NOT NULL,
    payment_id  TEXT        NOT NULL,
    currency    VARCHAR(3)  NOT NULL,
    amount      BIGINT      NOT NULL,
    fee         BIGINT      NOT NULL,
    closed_at   TIMESTAMPTZ
);
CREATE INDEX ledger_disputes_payment_idx ON ledger_disputes (payment_id);
