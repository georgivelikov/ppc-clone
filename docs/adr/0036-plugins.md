# 0036 — Plugins: WooCommerce gateway and a pay button

- **Status:** Accepted
- **Date:** 2026-10-22

## Context
Most merchants do not write payment integrations; they install a plugin. Two of the paths Paypercut offers are reproduced: a WooCommerce gateway, and something for any site that is not on a platform.

## Decision
- **WooCommerce: hosted-checkout redirect.** The gateway creates a checkout session and redirects; the card is typed on Cutpay's page. This keeps the shop out of card scope and needs no card field inside WordPress. Block checkout needs a small JavaScript registration, included.
- **An order is paid only on evidence the shop can verify.** The signed webhook (HMAC with a timestamp) or, when the customer returns before it arrives, a server-to-server read of the payment with the secret key. Never because the browser returned. Amount and currency must match the order, otherwise the order is held for a person.
- **Order lookup by stored payment id.** The webhook event has no metadata, and matching on an order number inside an event would let anyone who can create a payment pick an order. The plugin stores the payment id on the order when it creates the session and finds the order by that.
- **Retries are safe.** Session creation and refunds carry idempotency keys derived from the order and amount; events are de-duplicated on the order.
- **The pay button never creates a payment from the browser.** A page cannot hold a secret key, so `cutpay-button.js` either opens a payment link (made in the dashboard) or asks the shop's own server for a session URL. Addresses from attributes and from the server's answer must be http(s); anything else (`javascript:`) is rejected. It is published pinned with a Subresource Integrity hash like the SDK.
- **Tested against the real thing.** `plugins/woocommerce/integration.mjs` drives a real WordPress and WooCommerce (docker) and the real Cutpay stack: pay, webhook, forged webhook, refund, declined card. It found one real defect: the first version found the order through metadata that Cutpay's events do not carry.

## Not done
- Shopify app (needs a partner account and review), React Native merchant app (optional in the plan), subscriptions and saved cards in the plugin, WordPress.org packaging and translations.
- Only test mode was run end to end.
- The block checkout was verified to register, not clicked through in a browser.
- Cutpay's webhook events do not include the payment's metadata; adding it would let integrations correlate without storing ids, but the stored-id design is stronger anyway.

## Consequences
- The plugin has its own PHP tests (signature, money) run in CI; the WordPress integration run is manual because it needs the whole stack.
- The signature algorithm now exists in Java, TypeScript (demo shop) and PHP; the Java tests remain the specification.
