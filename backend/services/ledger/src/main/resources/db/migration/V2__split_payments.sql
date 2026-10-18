-- Split payments: part of a payment is credited to connected accounts, which are ledger parties of their own.
-- One row per destination of a payment; `reversed` tracks how much a refund has already taken back from it.
CREATE TABLE payment_transfers (
    payment_id  TEXT        NOT NULL,
    destination TEXT        NOT NULL,
    platform_id TEXT        NOT NULL,
    livemode    BOOLEAN     NOT NULL,
    currency    CHAR(3)     NOT NULL,
    gross       BIGINT      NOT NULL,   -- the whole payment, to share a refund in proportion
    amount      BIGINT      NOT NULL CHECK (amount > 0),
    reversed    BIGINT      NOT NULL DEFAULT 0 CHECK (reversed >= 0 AND reversed <= amount),
    PRIMARY KEY (payment_id, destination)
);

-- A payment now has a pending release per party: the platform and each connected account.
ALTER TABLE pending_releases DROP CONSTRAINT pending_releases_pkey;
ALTER TABLE pending_releases ADD PRIMARY KEY (payment_id, merchant_id);

-- Which platform a connected account belongs to, so only the platform can look at its balance.
CREATE TABLE connected_accounts (
    account_id  TEXT PRIMARY KEY,
    platform_id TEXT    NOT NULL,
    livemode    BOOLEAN NOT NULL
);
