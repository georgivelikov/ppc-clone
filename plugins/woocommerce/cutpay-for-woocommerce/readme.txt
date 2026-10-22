=== Cutpay for WooCommerce ===
Tags: payments, woocommerce, cutpay
Requires at least: 6.4
Tested up to: 7.1
Requires PHP: 7.4
Stable tag: 0.1.0
License: MIT

Take payments in WooCommerce through Cutpay's hosted checkout. Educational project: no real money moves.

== Description ==

Customers choose "Pay by card", are sent to Cutpay's secure page, and come back to the order. Card details never reach your site.

* Hosted checkout redirect (works with the classic and the block-based checkout)
* Orders are marked paid only by a signed webhook, or by a server-to-server read of the payment when the customer returns, never because the browser came back
* Amount and currency of every payment are checked against the order before it is marked paid
* Refunds from the WooCommerce order screen, full or partial
* Test mode and live mode with separate keys and webhook secrets
* Repeated webhooks and double clicks are harmless (idempotency keys, event de-duplication)

== Installation ==

1. Copy the `cutpay-for-woocommerce` folder to `wp-content/plugins/` and activate it.
2. WooCommerce > Settings > Payments > Cutpay: enable it, enter the API URL and your secret key.
3. In the Cutpay dashboard (Developers > Webhooks) add an endpoint with the URL shown under "Test webhook secret" (`https://your-shop/?wc-api=wc_gateway_cutpay`), subscribe it to `payment_intent.*`, and paste the endpoint's secret into the plugin.
4. Place a test order with the card 4242 4242 4242 4242.

== Changelog ==

= 0.1.0 =
First release.
