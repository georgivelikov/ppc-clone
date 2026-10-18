-- Money given back on an approved application. The refund id is the one payments gave the refund, so asking twice for the
-- same refund can never refund twice at the provider.
CREATE TABLE bnpl_refunds (
    refund_id      TEXT PRIMARY KEY,
    application_id TEXT        NOT NULL REFERENCES bnpl_applications (id),
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    -- pending: sent to the provider, no answer yet; succeeded / failed: the answer.
    status         TEXT        NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed')),
    failure        TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX bnpl_refunds_application_idx ON bnpl_refunds (application_id);

-- When the provider paid Cutpay for the application.
ALTER TABLE bnpl_applications ADD COLUMN settled_at TIMESTAMPTZ;
