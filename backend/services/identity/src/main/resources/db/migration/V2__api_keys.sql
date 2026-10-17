-- API keys (docs/adr/0009-api-keys.md). Only the SHA-256 hash of a key is stored; the raw value is shown once.
CREATE TABLE api_keys (
    id           TEXT PRIMARY KEY,
    merchant_id  TEXT        NOT NULL REFERENCES merchants (id),
    name         TEXT        NOT NULL,
    livemode     BOOLEAN     NOT NULL,
    type         TEXT        NOT NULL CHECK (type IN ('SECRET', 'PUBLISHABLE', 'RESTRICTED')),
    prefix       TEXT        NOT NULL, -- shown in the dashboard, e.g. sk_test_Ab3x
    key_hash     TEXT        NOT NULL UNIQUE,
    last4        TEXT        NOT NULL,
    scopes       TEXT[]      NOT NULL DEFAULT '{}',
    created_by   TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ
);
CREATE INDEX api_keys_merchant_idx ON api_keys (merchant_id, created_at DESC);
