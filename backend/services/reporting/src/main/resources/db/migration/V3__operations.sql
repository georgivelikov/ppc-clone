-- Operations (docs/adr/0032-resilience-and-reconciliation.md): the daily three-way reconciliation of what payments, the
-- ledger and the acquirer each say happened, and what staff decided about each dead-lettered record.

CREATE TABLE reconciliation_runs (
    id                TEXT PRIMARY KEY,
    scope_date        DATE,                     -- the UTC day compared; null means everything the acquirer file holds
    status            TEXT        NOT NULL CHECK (status IN ('running', 'clean', 'mismatched', 'failed')),
    payments_checked  INT         NOT NULL DEFAULT 0,
    refunds_checked   INT         NOT NULL DEFAULT 0,
    acquirer_lines    INT         NOT NULL DEFAULT 0,
    mismatch_count    INT         NOT NULL DEFAULT 0,
    error             TEXT,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE TABLE reconciliation_mismatches (
    id               BIGSERIAL PRIMARY KEY,
    run_id           TEXT        NOT NULL REFERENCES reconciliation_runs (id),
    kind             TEXT        NOT NULL,
    payment_id       TEXT        NOT NULL,
    currency         VARCHAR(3),
    payments_amount  BIGINT,
    ledger_amount    BIGINT,
    acquirer_amount  BIGINT,
    detail           TEXT        NOT NULL
);
CREATE INDEX reconciliation_mismatches_run_idx ON reconciliation_mismatches (run_id);

-- What was done with a record on a dead-letter topic. The record itself stays on the topic; this says whether it still needs a person.
CREATE TABLE dead_letter_actions (
    topic        TEXT        NOT NULL,
    partition_no INT         NOT NULL,
    record_offset BIGINT     NOT NULL,
    action       TEXT        NOT NULL CHECK (action IN ('redriven', 'discarded')),
    staff_email  TEXT,
    acted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (topic, partition_no, record_offset)
);
