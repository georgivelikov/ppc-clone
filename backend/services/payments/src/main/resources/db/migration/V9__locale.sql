-- The language a merchant asked the hosted checkout to open in for this payment (en, bg, ro, pl, hu or el).
ALTER TABLE payment_intents ADD COLUMN locale TEXT;
