-- Payouts: bank accounts, schedules, payouts and the ledger entries each payout settles
-- (docs/plans/phase-02-ledger-payouts.md).

CREATE TABLE bank_accounts (
    id          TEXT PRIMARY KEY,               -- ba_...
    merchant_id TEXT        NOT NULL,
    livemode    BOOLEAN     NOT NULL,
    iban        TEXT        NOT NULL,
    holder_name TEXT        NOT NULL,
    currency    CHAR(3)     NOT NULL,
    status      TEXT        NOT NULL CHECK (status IN ('active', 'removed')),
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX bank_accounts_merchant_idx ON bank_accounts (merchant_id, livemode, status);

CREATE TABLE payout_schedules (
    merchant_id   TEXT PRIMARY KEY,
    schedule      TEXT        NOT NULL CHECK (schedule IN ('daily', 'weekly', 'manual')),
    delay_days    INT         NOT NULL CHECK (delay_days BETWEEN 0 AND 30),
    weekly_anchor TEXT        NOT NULL DEFAULT 'MONDAY',
    updated_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE payouts (
    id              TEXT PRIMARY KEY,           -- po_...
    merchant_id     TEXT        NOT NULL,
    livemode        BOOLEAN     NOT NULL,
    amount          BIGINT      NOT NULL CHECK (amount > 0),  -- what reaches the bank
    fee             BIGINT      NOT NULL DEFAULT 0 CHECK (fee >= 0),  -- instant-payout fee, on top
    currency        CHAR(3)     NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('pending', 'in_transit', 'paid', 'failed')),
    kind            TEXT        NOT NULL CHECK (kind IN ('scheduled', 'instant')),
    arrival_date    DATE        NOT NULL,
    bank_account_id TEXT        NOT NULL REFERENCES bank_accounts (id),
    failure_code    TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX payouts_merchant_idx ON payouts (merchant_id, livemode, id DESC);
CREATE INDEX payouts_in_transit_idx ON payouts (created_at) WHERE status = 'in_transit';

-- The payable balance, as announced by the ledger: one row per FundsAvailable event. A payout claims the
-- rows nobody has claimed yet. Negative rows (a refund of funds that were already available) reduce the
-- next payout.
CREATE TABLE available_entries (
    entry_id    TEXT PRIMARY KEY,               -- the ledger journal entry
    merchant_id TEXT        NOT NULL,
    livemode    BOOLEAN     NOT NULL,
    currency    CHAR(3)     NOT NULL,
    amount      BIGINT      NOT NULL CHECK (amount <> 0),
    source_id   TEXT        NOT NULL,
    payout_id   TEXT REFERENCES payouts (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX available_entries_unclaimed_idx ON available_entries (merchant_id, livemode, currency)
    WHERE payout_id IS NULL;

-- Reconciliation: which ledger entries are inside which payout.
CREATE TABLE payout_items (
    payout_id         TEXT   NOT NULL REFERENCES payouts (id),
    journal_entry_ref TEXT   NOT NULL REFERENCES available_entries (entry_id),
    source_id         TEXT   NOT NULL,
    amount            BIGINT NOT NULL,
    PRIMARY KEY (payout_id, journal_entry_ref)
);

-- One scheduled payout per merchant, mode and currency per (simulated) day.
CREATE TABLE payout_runs (
    merchant_id TEXT    NOT NULL,
    livemode    BOOLEAN NOT NULL,
    currency    CHAR(3) NOT NULL,
    run_date    DATE    NOT NULL,
    PRIMARY KEY (merchant_id, livemode, currency, run_date)
);

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

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
