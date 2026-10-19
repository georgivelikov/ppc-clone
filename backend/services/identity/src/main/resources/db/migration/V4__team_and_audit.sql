-- Team invitations (docs/adr/0025-team-roles-and-permissions.md). Only a hash of the invite token is stored,
-- like refresh tokens and API keys: the link is shown once to whoever sends the invite.
CREATE TABLE invitations (
    id          TEXT PRIMARY KEY,
    merchant_id TEXT        NOT NULL REFERENCES merchants (id),
    email       TEXT        NOT NULL,
    role        TEXT        NOT NULL CHECK (role IN ('admin', 'developer', 'viewer', 'support')),
    token_hash  TEXT        NOT NULL UNIQUE,
    invited_by  TEXT        NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX invitations_merchant_idx ON invitations (merchant_id, created_at DESC);
-- One open invitation per person and business.
CREATE UNIQUE INDEX invitations_open_idx ON invitations (merchant_id, lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

-- Who did what, for the Settings, Security screen. Rows are only ever added.
CREATE TABLE audit_events (
    id          BIGSERIAL PRIMARY KEY,
    merchant_id TEXT        NOT NULL,
    actor_id    TEXT,
    actor_email TEXT,
    action      TEXT        NOT NULL,
    target_type TEXT,
    target_id   TEXT,
    detail      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_merchant_idx ON audit_events (merchant_id, id DESC);
