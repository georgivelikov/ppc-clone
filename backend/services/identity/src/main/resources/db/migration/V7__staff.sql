-- Cutpay's own people, in a realm of their own: they sign in at /auth/staff/login, their tokens carry no business, and the gateway
-- lets them reach only /v1/staff/** (docs/adr/0030-back-office.md).
CREATE TABLE staff_users (
    id            TEXT PRIMARY KEY,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    full_name     TEXT        NOT NULL,
    role          TEXT        NOT NULL CHECK (role IN ('reviewer', 'admin')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX staff_users_email_idx ON staff_users (lower(email));

-- What staff have noted about a business: watch it, hold its payouts, and so on.
CREATE TABLE merchant_flags (
    merchant_id TEXT        NOT NULL REFERENCES merchants (id),
    flag        TEXT        NOT NULL,
    note        TEXT,
    set_by      TEXT        NOT NULL,
    set_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, flag)
);
