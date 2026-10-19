-- Disputes (chargebacks): a cardholder asks their bank for the money back, and the merchant may answer with evidence.

-- Set from the card network when the card is tokenised: the cardholder will dispute this payment that many days after it succeeds.
ALTER TABLE payment_attempts ADD COLUMN dispute_after_days INT;

-- Payments that will be disputed, and when. Rows are added as the payment succeeds and opened by a job on the
-- (simulated) clock.
CREATE TABLE dispute_schedule (
    payment_id TEXT PRIMARY KEY REFERENCES payment_intents (id),
    open_at    TIMESTAMPTZ NOT NULL,
    opened_at  TIMESTAMPTZ
);
CREATE INDEX dispute_schedule_due_idx ON dispute_schedule (open_at) WHERE opened_at IS NULL;

CREATE TABLE disputes (
    id                    TEXT PRIMARY KEY,
    merchant_id           TEXT        NOT NULL,
    livemode              BOOLEAN     NOT NULL,
    payment_id            TEXT        NOT NULL REFERENCES payment_intents (id),
    amount                BIGINT      NOT NULL CHECK (amount > 0),
    currency              VARCHAR(3)  NOT NULL,
    fee                   BIGINT      NOT NULL,
    reason                TEXT        NOT NULL,
    status                TEXT        NOT NULL CHECK (status IN ('needs_response', 'under_review', 'won', 'lost')),
    evidence_text         TEXT,
    evidence_due_by       TIMESTAMPTZ NOT NULL,
    evidence_submitted_at TIMESTAMPTZ,
    review_started_at     TIMESTAMPTZ,       -- real time: the network takes a moment, not days
    outcome_reason        TEXT,
    opened_at             TIMESTAMPTZ NOT NULL,
    closed_at             TIMESTAMPTZ
);
CREATE INDEX disputes_merchant_idx ON disputes (merchant_id, livemode, id DESC);
CREATE INDEX disputes_payment_idx ON disputes (payment_id);
CREATE INDEX disputes_open_idx ON disputes (status) WHERE status IN ('needs_response', 'under_review');

-- Evidence documents. They live in the database rather than in object storage (docs/adr/0027-disputes.md).
CREATE TABLE dispute_files (
    id           TEXT PRIMARY KEY,
    dispute_id   TEXT        NOT NULL REFERENCES disputes (id),
    filename     TEXT        NOT NULL,
    content_type TEXT        NOT NULL,
    size         INT         NOT NULL,
    content      BYTEA       NOT NULL,
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX dispute_files_dispute_idx ON dispute_files (dispute_id);
