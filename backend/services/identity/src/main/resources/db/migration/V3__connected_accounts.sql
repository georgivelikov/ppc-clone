-- Connected accounts: the sub-merchants of a platform (restaurants of a delivery app, sellers of a marketplace).
-- They receive the part of a payment the platform sends them and are paid out to their own bank account.
CREATE TABLE connected_accounts (
    id                   TEXT PRIMARY KEY,
    platform_merchant_id TEXT        NOT NULL REFERENCES merchants (id),
    livemode             BOOLEAN     NOT NULL,
    name                 TEXT        NOT NULL,
    email                TEXT,
    iban                 TEXT        NOT NULL,
    country              VARCHAR(2)  NOT NULL,
    currency             VARCHAR(3)  NOT NULL,
    status               TEXT        NOT NULL CHECK (status IN ('active', 'disabled')),
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX connected_accounts_platform_idx ON connected_accounts (platform_merchant_id, livemode, id DESC);
