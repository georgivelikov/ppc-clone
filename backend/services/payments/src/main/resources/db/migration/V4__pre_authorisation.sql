-- Pre-authorisation: money is held on the card first (requires_capture) and captured later, in full or in part.
-- amount_authorized is what was held; once captured, amount becomes what was really taken.
ALTER TABLE payment_intents ADD COLUMN amount_authorized BIGINT;
ALTER TABLE payment_intents ADD COLUMN capture_before TIMESTAMPTZ;
CREATE INDEX payment_intents_capture_due_idx ON payment_intents (capture_before)
    WHERE status = 'REQUIRES_CAPTURE';

-- Lock table for scheduled jobs (see JobLock in common-events).
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
