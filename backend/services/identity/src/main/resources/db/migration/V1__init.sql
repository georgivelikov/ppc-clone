CREATE TABLE merchants (
    id               TEXT PRIMARY KEY,
    legal_name       TEXT        NOT NULL,
    display_name     TEXT        NOT NULL,
    country          VARCHAR(2)  NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
    default_currency VARCHAR(3)  NOT NULL CHECK (default_currency ~ '^[A-Z]{3}$'),
    status           TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    full_name     TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);
-- Emails are case-insensitive.
CREATE UNIQUE INDEX users_email_lower_idx ON users (lower(email));

CREATE TABLE memberships (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users (id),
    merchant_id TEXT NOT NULL REFERENCES merchants (id),
    role        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, merchant_id)
);

CREATE TABLE refresh_tokens (
    id          TEXT PRIMARY KEY,
    user_id     TEXT        NOT NULL REFERENCES users (id),
    merchant_id TEXT        NOT NULL REFERENCES merchants (id),
    token_hash  TEXT        NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

-- RSA key pairs used to sign access tokens (docs/adr/0003-authentication.md).
CREATE TABLE signing_keys (
    kid         TEXT PRIMARY KEY,
    private_key TEXT        NOT NULL, -- PKCS#8, base64
    public_key  TEXT        NOT NULL, -- X.509, base64
    active      BOOLEAN     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX signing_keys_single_active_idx ON signing_keys (active) WHERE active;

-- Transactional outbox + consumer inbox (docs/adr/0005-outbox-relay.md).
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
