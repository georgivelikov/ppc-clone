-- Pay in instalments: a payment can be settled by a buy-now-pay-later provider instead of a card. The provider pays
-- Cutpay (less its fee) and the customer pays the provider, so the merchant is paid up front.
ALTER TABLE payment_intents ADD COLUMN payment_method_type TEXT NOT NULL DEFAULT 'card';
ALTER TABLE payment_intents ADD COLUMN bnpl_provider TEXT;
ALTER TABLE payment_intents ADD COLUMN bnpl_application_id TEXT;
-- What the provider keeps, so the ledger can pay the merchant the rest.
ALTER TABLE payment_intents ADD COLUMN bnpl_fee BIGINT;
