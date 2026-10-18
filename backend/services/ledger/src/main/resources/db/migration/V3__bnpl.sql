-- A buy-now-pay-later payment is not cash yet: the provider owes Cutpay the price less its fee until it settles. That
-- amount sits in a receivable account of the platform.
ALTER TABLE accounts DROP CONSTRAINT accounts_type_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_type_check
    CHECK (type IN ('merchant_pending', 'merchant_available', 'merchant_reserve', 'platform_fees', 'platform_cash',
                    'payouts_in_transit', 'customer_funds', 'bnpl_receivable'));
