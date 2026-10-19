-- Foreign exchange: rates, locked quotes, conversions between a merchant's balances, and what each merchant wants done
-- with money that arrives in a currency they do not settle in.

-- The platform takes the other side of every conversion in an account of its own, one per currency.
ALTER TABLE accounts DROP CONSTRAINT accounts_type_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_type_check
    CHECK (type IN ('merchant_pending', 'merchant_available', 'merchant_reserve', 'platform_fees', 'platform_cash',
                    'payouts_in_transit', 'customer_funds', 'bnpl_receivable', 'platform_fx'));

-- Every rate is quoted per one euro (EUR/PLN = 4.28 means 1 EUR buys 4.28 PLN); the rate between any two other
-- currencies is worked out from these. Rows are only added, so the history of a rate is kept.
CREATE TABLE fx_rates (
    id     BIGSERIAL PRIMARY KEY,
    base   CHAR(3)       NOT NULL,
    quote  CHAR(3)       NOT NULL,
    rate   NUMERIC(18, 8) NOT NULL CHECK (rate > 0),
    source TEXT          NOT NULL,
    as_of  TIMESTAMPTZ   NOT NULL
);
CREATE INDEX fx_rates_latest_idx ON fx_rates (base, quote, as_of DESC, id DESC);

INSERT INTO fx_rates (base, quote, rate, source, as_of) VALUES
    ('EUR', 'RON', 4.97000000, 'seed', now()),
    ('EUR', 'PLN', 4.28000000, 'seed', now()),
    ('EUR', 'HUF', 395.00000000, 'seed', now()),
    ('EUR', 'CZK', 24.40000000, 'seed', now()),
    ('EUR', 'GBP', 0.86000000, 'seed', now()),
    ('EUR', 'USD', 1.08000000, 'seed', now()),
    ('EUR', 'CHF', 0.95000000, 'seed', now());

-- What a merchant wants done with money in other currencies. The markup is the platform's margin on a conversion.
CREATE TABLE merchant_currency_settings (
    merchant_id           TEXT PRIMARY KEY,
    settlement_currencies TEXT[]      NOT NULL,
    auto_convert          BOOLEAN     NOT NULL DEFAULT false,
    fx_markup_bps         INT         NOT NULL DEFAULT 100 CHECK (fx_markup_bps BETWEEN 0 AND 1000),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A rate promised to a merchant for 15 minutes, for one conversion.
CREATE TABLE fx_quotes (
    id            TEXT PRIMARY KEY,
    merchant_id   TEXT          NOT NULL,
    livemode      BOOLEAN       NOT NULL,
    from_currency CHAR(3)       NOT NULL,
    to_currency   CHAR(3)       NOT NULL,
    from_amount   BIGINT        NOT NULL,
    mid_amount    BIGINT        NOT NULL,
    to_amount     BIGINT        NOT NULL,
    rate          NUMERIC(24, 12) NOT NULL,
    markup_bps    INT           NOT NULL,
    expires_at    TIMESTAMPTZ   NOT NULL,
    used_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL
);
CREATE INDEX fx_quotes_merchant_idx ON fx_quotes (merchant_id, id DESC);

-- A conversion, with the two journal entries (one per currency) that record it.
CREATE TABLE fx_conversions (
    id             TEXT PRIMARY KEY,
    merchant_id    TEXT          NOT NULL,
    livemode       BOOLEAN       NOT NULL,
    from_currency  CHAR(3)       NOT NULL,
    to_currency    CHAR(3)       NOT NULL,
    from_amount    BIGINT        NOT NULL,
    mid_amount     BIGINT        NOT NULL,   -- what the amount is worth at the market rate
    to_amount      BIGINT        NOT NULL,   -- what the merchant receives
    markup_amount  BIGINT        NOT NULL,   -- mid_amount - to_amount, kept by the platform
    rate           NUMERIC(24, 12) NOT NULL,
    markup_bps     INT           NOT NULL,
    kind           TEXT          NOT NULL CHECK (kind IN ('manual', 'auto')),
    quote_id       TEXT,
    source_id      TEXT,                     -- the payment whose funds were converted (auto)
    from_entry_id  TEXT          NOT NULL REFERENCES journal_entries (id),
    to_entry_id    TEXT          NOT NULL REFERENCES journal_entries (id),
    created_at     TIMESTAMPTZ   NOT NULL
);
CREATE INDEX fx_conversions_merchant_idx ON fx_conversions (merchant_id, livemode, id DESC);
