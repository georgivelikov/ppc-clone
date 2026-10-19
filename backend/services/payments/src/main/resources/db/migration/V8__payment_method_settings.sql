-- Which ways of paying a merchant has switched off. Only the exceptions are stored: a method with no row is on.
CREATE TABLE payment_method_settings (
    merchant_id TEXT        NOT NULL,
    method      TEXT        NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, method)
);
