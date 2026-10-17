-- Double-entry ledger (docs/plans/phase-02-ledger-payouts.md).
-- Sign convention: postings.amount is positive for a debit and negative for a credit, so every merchant
-- balance (a liability of the platform) is stored as a negative number and shown to the merchant negated.

CREATE TABLE accounts (
    id          TEXT PRIMARY KEY,
    merchant_id TEXT,                        -- NULL for platform-wide accounts
    livemode    BOOLEAN     NOT NULL,
    type        TEXT        NOT NULL CHECK (type IN ('merchant_pending', 'merchant_available', 'merchant_reserve',
                                                     'platform_fees', 'platform_cash', 'payouts_in_transit',
                                                     'customer_funds')),
    currency    CHAR(3)     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT accounts_unique UNIQUE NULLS NOT DISTINCT (merchant_id, livemode, type, currency)
);
CREATE INDEX accounts_merchant_idx ON accounts (merchant_id, livemode);

CREATE TABLE journal_entries (
    id          TEXT PRIMARY KEY,
    event_id    TEXT        NOT NULL UNIQUE,   -- the event that caused the entry: replays cannot post twice
    entry_type  TEXT        NOT NULL,
    description TEXT        NOT NULL,
    merchant_id TEXT,
    livemode    BOOLEAN     NOT NULL,
    currency    CHAR(3)     NOT NULL,
    source_id   TEXT,                          -- pay_..., re_..., po_...
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX journal_entries_merchant_idx ON journal_entries (merchant_id, id DESC);

CREATE TABLE postings (
    id               BIGSERIAL PRIMARY KEY,
    journal_entry_id TEXT        NOT NULL REFERENCES journal_entries (id),
    account_id       TEXT        NOT NULL REFERENCES accounts (id),
    amount           BIGINT      NOT NULL CHECK (amount <> 0),
    currency         CHAR(3)     NOT NULL
);
CREATE INDEX postings_entry_idx ON postings (journal_entry_id);
CREATE INDEX postings_account_idx ON postings (account_id);

-- Invariant: the postings of one journal entry sum to zero. Checked when the transaction commits, so an
-- entry can be written line by line.
CREATE FUNCTION check_entry_balanced() RETURNS trigger AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO total FROM postings WHERE journal_entry_id = NEW.journal_entry_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced (sum = %)', NEW.journal_entry_id, total;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();

-- Postings are history: corrections are new entries, never edits.
CREATE FUNCTION postings_are_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'postings are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER postings_no_update_delete
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION postings_are_immutable();

-- Materialised running total per account, updated in the same transaction as the postings.
CREATE TABLE balances (
    account_id TEXT   PRIMARY KEY REFERENCES accounts (id),
    amount     BIGINT NOT NULL,
    version    BIGINT NOT NULL
);

-- What the merchant sees on the Balances page: one row per payment, refund or payout.
CREATE TABLE balance_transactions (
    id               TEXT PRIMARY KEY,         -- txn_...
    merchant_id      TEXT        NOT NULL,
    livemode         BOOLEAN     NOT NULL,
    currency         CHAR(3)     NOT NULL,
    type             TEXT        NOT NULL,     -- payment | refund | payout | payout_failure
    source_id        TEXT        NOT NULL,     -- pay_..., re_..., po_...
    parent_id        TEXT,                     -- the payment a refund belongs to
    gross            BIGINT      NOT NULL,
    fee              BIGINT      NOT NULL,
    net              BIGINT      NOT NULL,
    description      TEXT        NOT NULL,
    journal_entry_id TEXT        NOT NULL REFERENCES journal_entries (id),
    available_on     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX balance_transactions_merchant_idx ON balance_transactions (merchant_id, livemode, id DESC);
CREATE INDEX balance_transactions_source_idx ON balance_transactions (merchant_id, source_id);
CREATE INDEX balance_transactions_parent_idx ON balance_transactions (merchant_id, parent_id);

-- Funds that are still pending, one row per payment. `remaining` is what is left to move to available
-- (refunds before release reduce it, so it can even be negative when the refund exceeds the net amount).
CREATE TABLE pending_releases (
    payment_id   TEXT PRIMARY KEY,
    merchant_id  TEXT        NOT NULL,
    livemode     BOOLEAN     NOT NULL,
    currency     CHAR(3)     NOT NULL,
    remaining    BIGINT      NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    released_at  TIMESTAMPTZ
);
CREATE INDEX pending_releases_due_idx ON pending_releases (available_at) WHERE released_at IS NULL;

-- Per merchant pricing, overridable (docs/plans/phase-02-ledger-payouts.md#pricing). Rates are in basis
-- points (129 = 1.29%), fixed fees in minor units of the payment's currency.
CREATE TABLE pricing_plans (
    merchant_id        TEXT PRIMARY KEY,
    eea_consumer_bps   INT         NOT NULL,
    eea_consumer_fixed BIGINT      NOT NULL,
    other_bps          INT         NOT NULL,
    other_fixed        BIGINT      NOT NULL,
    delay_days         INT         NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
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
