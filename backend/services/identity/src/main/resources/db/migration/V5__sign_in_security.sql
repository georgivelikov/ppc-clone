-- Sign-in security: email verification, two-factor codes, recovery codes, password reset, a list of sessions.
ALTER TABLE users
    ADD COLUMN email_verified_at TIMESTAMPTZ,
    ADD COLUMN totp_secret       TEXT,          -- set when setup starts, in force once totp_enabled_at is set
    ADD COLUMN totp_enabled_at   TIMESTAMPTZ;

CREATE TABLE recovery_codes (
    id        BIGSERIAL PRIMARY KEY,
    user_id   TEXT        NOT NULL REFERENCES users (id),
    code_hash TEXT        NOT NULL,
    used_at   TIMESTAMPTZ
);
CREATE INDEX recovery_codes_user_idx ON recovery_codes (user_id);

-- Single-use links sent by email. Only a hash of the token is stored.
CREATE TABLE one_time_tokens (
    id         TEXT PRIMARY KEY,
    user_id    TEXT        NOT NULL REFERENCES users (id),
    purpose    TEXT        NOT NULL CHECK (purpose IN ('password_reset', 'email_verification')),
    token_hash TEXT        NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A session is a chain of refresh tokens: each rotation keeps the family of the token it replaces.
ALTER TABLE refresh_tokens
    ADD COLUMN family_id  TEXT,
    ADD COLUMN user_agent TEXT,
    ADD COLUMN rotated_at TIMESTAMPTZ; -- set when a refresh replaced it: showing it again is theft, unlike a sign-out
UPDATE refresh_tokens SET rotated_at = revoked_at WHERE revoked_at IS NOT NULL;
UPDATE refresh_tokens SET family_id = id;
ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
