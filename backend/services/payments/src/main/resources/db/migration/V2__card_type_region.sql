-- Issuer classification from the acquirer's BIN lookup; the ledger prices a payment by it (phase 2).
ALTER TABLE payment_attempts ADD COLUMN card_type   TEXT;
ALTER TABLE payment_attempts ADD COLUMN card_region TEXT;
