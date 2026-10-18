# 0015 — Saved cards, pre-authorisation and billing

- **Status:** Accepted
- **Date:** 2026-10-18

## Context
Phase 5 adds three things that look alike from far away and are not: holding money and taking it later, charging a saved card without the customer, and billing on a schedule. Each has a time dimension, so each needs a decision about what is true while we wait, and what happens when the clock moves.

## Decision

### Pre-authorisation is a payment state, not a second payment
`capture_method = manual` makes the issuer approve the amount and stop there: the payment waits in `requires_capture` (`amount_authorized` remembers what is held, `capture_before` when it lapses). `POST /capture` takes all or part; what is not taken is released and the payment becomes a payment for the captured amount only (`amount` shrinks, `amount_authorized` keeps the history). This keeps one rule for everything downstream: refund limits, fees and the ledger look at `amount` and nothing else, and the `PaymentSucceeded` event of a capture carries the captured amount.

- The call to the card network is made outside the database transaction, like a payment. Capturing twice with the same amount is a harmless retry at the acquirer, with another amount it is refused, so a lost answer can be repeated.
- A hold nobody captures is released after seven days by a job (`JobLock`, simulated clock): the acquirer is told first, then the payment becomes `canceled` with `failure_code = authorization_expired`. If the acquirer is unreachable the payment is simply tried again on the next run.
- Pre-authorisation and split payments are mutually exclusive: a partial capture would otherwise have to re-divide the transfers.

### Saved cards are pointers to the vault
A customer owns payment methods (`pm_`): brand, last four, expiry, fingerprint and the vault token. The same card (fingerprint + expiry) is saved once per customer. A card is saved by a **setup intent**: a hosted page, a verification at the bank without any amount, and a 3-D Secure challenge once if the bank wants one. A payment can also save the card it used (`setup_future_usage`).

An **off-session** payment (`off_session: true`) is charged immediately to a saved card. Nobody is there to answer a challenge, so the simulated issuer behaves like a real one: a card that was authenticated when it was saved can be charged (`4000 0000 0000 3220`), a card that wants the customer every time cannot and fails with `authentication_required` (`4000 0027 6000 3184`), and `4000 0000 0000 0341` can be saved but is declined at every charge. That last number exists to test dunning.

### Billing is its own service, and talks to payments over HTTP
`billing` owns products, prices, subscriptions, invoices and retries; customers, cards and every charge stay in `payments`. Billing creates payments through the internal endpoint (the same one payment links use) and learns the outcome two ways: the answer of the call, and the `payment.events` stream (payments made for an invoice carry `invoice_id` in their metadata). Both end in the same idempotent step, so an invoice is paid once and a failure counted once, whichever arrives first. The call is made after the invoice is committed and the result recorded in a second transaction: the same shape as a payment, for the same reason (never hold a database lock across a call to somebody else).

Prices are immutable. A subscription therefore keeps the terms it signed up for and "change the price" is "archive and add".

### Time in a subscription
- **Billed in advance.** The invoice for a period is created when the period starts. A trial is period `-1`, so the first paid period is index 0 and starts at the end of the trial.
- **Anchored periods.** Period *k* starts at `anchor + k intervals`, always counted from the anchor. Adding a month to the previous end instead would drift a subscription that began on the 31st to the 28th for ever after February. A unit test pins the 31st → 28th → 31st → 30th sequence and leap days.
- **One billing run** (every few seconds, `JobLock`) takes each due subscription under its row lock, so two workers cannot bill the same period; a test runs four workers at once.
- **Dunning** retries on days 1, 3, 5 and 7 after the *first* failure (configurable). Each failure schedules the next attempt, so a retry that finally succeeds drops the rest. After the last failure the merchant's rule applies: cancel the subscription or leave it `unpaid`; the invoice becomes `uncollectible` either way, and the customer can still pay it from the hosted page.
- A first invoice that fails leaves the subscription `incomplete`. After 24 hours it is canceled and the invoice voided.

### Proration
Changing the items in the middle of a period credits the unused part of what changed and charges the rest of the period at the new items, using whole seconds left in the period and rounding half up to the minor unit (a credit mirrors its charge). Items that did not change produce nothing. The lines wait as pending invoice items and join the next invoice, or are invoiced and charged at once with `bill_immediately`. A change during a trial, or with `proration_behavior: none`, produces no lines. `proration_preview` runs the same function without writing, so the preview and the result agree to the cent unless time passes between them.

### Invoices
A draft is editable and invisible to the customer. Finalizing assigns the next number of the merchant and mode from a counter (INV-0001, never reused, gaps only when a draft is deleted before it is numbered), emails the link and opens it for payment. The hosted page at `/i/{token}` is authorised by an unguessable token like a client secret and rate limited at the gateway. Paying there creates an ordinary checkout payment tagged with the invoice. Emails are recorded in `notifications` and logged until real delivery arrives in phase 8.

## Consequences and known gaps
- If payments is unreachable *after* it created and charged a payment, billing treats the attempt as failed and may charge again at the next retry; the payment event for the first charge then marks the invoice paid and a later success would be a second charge (it is logged). A reconciliation pass over payments by `invoice_id` would close this.
- A credit that exceeds the next invoice is dropped instead of becoming a customer balance.
- Tax is a single percentage per subscription or invoice, rounded half up on the whole subtotal. There is no tax engine.
- MRR counts active and past-due subscriptions at the monthly value of their items (a week is 52/12 months). Churn is the share of the paying subscriptions of the last 30 days that ended.
- Cancelling refunds nothing, and there is no proration on cancel.
