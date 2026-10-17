-- online: hosted checkout and the API. moto: mail/telephone orders keyed in at the virtual terminal.
ALTER TABLE payment_intents ADD COLUMN channel TEXT NOT NULL DEFAULT 'online';
