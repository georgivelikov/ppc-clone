-- Payouts held back by the back-office (docs/adr/0030-back-office.md). A frozen business keeps receiving money and can see its
-- balance, but nothing leaves for its bank account until the freeze is lifted.
CREATE TABLE payout_freezes (
    merchant_id TEXT PRIMARY KEY,
    frozen_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
