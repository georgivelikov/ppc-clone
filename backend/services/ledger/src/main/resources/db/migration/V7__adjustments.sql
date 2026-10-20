-- Manual corrections by Cutpay staff, with four eyes: one admin asks, a different admin approves. Only an approved one
-- touches the books (docs/adr/0030-back-office.md).
CREATE TABLE adjustments (
    id           TEXT PRIMARY KEY,
    merchant_id  TEXT        NOT NULL,
    livemode     BOOLEAN     NOT NULL,
    currency     VARCHAR(3)  NOT NULL,
    amount       BIGINT      NOT NULL CHECK (amount <> 0),   -- positive pays the merchant, negative takes from them
    reason       TEXT        NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('pending', 'approved', 'rejected')),
    requested_by TEXT        NOT NULL,
    decided_by   TEXT,
    decision_note TEXT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ,
    CHECK (decided_by IS NULL OR decided_by <> requested_by)
);
CREATE INDEX adjustments_status_idx ON adjustments (status, requested_at DESC);
