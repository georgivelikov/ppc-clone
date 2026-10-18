-- Split payments: part of a payment is sent to connected accounts (the sub-merchants of a platform), and the platform
-- may keep an application fee. What is left of the payment, minus the processing fee, stays with the platform.
CREATE TABLE payment_transfers (
    id                TEXT PRIMARY KEY,
    payment_intent_id TEXT   NOT NULL REFERENCES payment_intents (id),
    destination       TEXT   NOT NULL,
    amount            BIGINT NOT NULL CHECK (amount > 0)
);
CREATE INDEX payment_transfers_intent_idx ON payment_transfers (payment_intent_id);

ALTER TABLE payment_intents ADD COLUMN application_fee_amount BIGINT;
