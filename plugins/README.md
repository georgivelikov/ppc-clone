# Plugins

Ways to add Cutpay to a site without writing a payments integration.

| | What | Where |
|---|---|---|
| **WooCommerce** | A payment gateway (PHP): hosted-checkout redirect, signed webhook, refunds from the order screen, block and classic checkout | `plugins/woocommerce/cutpay-for-woocommerce/` |
| **Pay button** | A script for any page: `<div data-cutpay-button data-link="...">` opens a payment link; `data-endpoint` asks the shop's server for a session | `frontend/packages/checkout-js/src/button.ts`, served at `/v1/cutpay-button.js` |

Not built: a Shopify app (it needs a Shopify partner account and review) and the React Native merchant app (optional in the plan).

## The WooCommerce plugin

**How an order is paid**
1. The customer picks Cutpay and places the order. The plugin creates a checkout session on Cutpay (server to server, with the secret key and an idempotency key built from the order and amount) and redirects the customer there. The order is `on-hold`.
2. The customer pays on Cutpay and returns to the order page.
3. The order becomes `processing` only through a channel the shop can trust: the signed `payment_intent.succeeded` webhook, or, if the customer is back before the webhook, a server-to-server read of the payment with the secret key. Before marking paid it checks the amount and currency against the order; a mismatch leaves the order on hold with a note.
4. A refund from the order screen calls `POST /v1/refunds`; partial refunds work.

**Choices worth knowing**
- *The order is found by the stored payment id*, not by a number inside the event. The event carries no metadata, and trusting an order number in an event would let anyone who can create a payment name any order.
- *Redirect, not embedded.* The hosted page keeps card data off the shop (the smallest PCI scope, see `docs/security/pci-dss.md`). An embedded card field would be a second integration.
- *Money goes through strings.* `Cutpay_Money` converts decimals to whole minor units with one rounding; floats never decide the amount.
- *Signature check is a copy of the Java one* (`Cutpay-Signature: t=...,v1=...`, HMAC-SHA256 over `t.body`, five-minute tolerance, several signatures allowed during rotation).

**Tests**
```
# Pure logic (signature, money): no WordPress needed
docker run --rm -v "$PWD/plugins/woocommerce/cutpay-for-woocommerce:/p" php:8.3-cli php /p/tests/run.php

# The whole thing, against a real WordPress + WooCommerce and the real Cutpay stack
docker compose -f infra/docker-compose.yml up -d
node e2e/chaos/stack.mjs up
docker compose -f plugins/woocommerce/docker-compose.yml up -d
W="docker compose -f plugins/woocommerce/docker-compose.yml run --rm -T wpcli wp"
$W core install --url=http://localhost:8099 --title="Test Shop" --admin_user=admin --admin_password=admin --admin_email=admin@example.com --skip-email
$W plugin install woocommerce --activate && $W plugin activate cutpay-for-woocommerce
node plugins/woocommerce/integration.mjs
```
`integration.mjs` places an order, pays it on Cutpay, checks the webhook marks it paid, checks a forged webhook is refused, refunds 10.00 from WooCommerce and checks Cutpay shows it, and checks a declined card leaves the order unpaid. All 11 checks passed against WordPress 7.1 and the current WooCommerce.

**Not covered**: live-mode keys (only test mode was run), subscriptions, saved cards, multi-currency plugins, and the block checkout was checked for registration, not clicked through in a browser.
