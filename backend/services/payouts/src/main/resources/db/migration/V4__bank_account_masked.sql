-- The IBAN is stored encrypted (Vault transit; see ADR 0034). What a response shows, the country and the last four
-- digits, is not secret and is kept beside it so that listing accounts never needs the key.
ALTER TABLE bank_accounts ADD COLUMN iban_country TEXT;
ALTER TABLE bank_accounts ADD COLUMN iban_last4   TEXT;

UPDATE bank_accounts
   SET iban_country = left(iban, 2),
       iban_last4   = right(iban, 4)
 WHERE iban NOT LIKE 'vault:%' AND iban NOT LIKE 'local:%';
