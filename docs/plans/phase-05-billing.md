# Phase 5 — Billing: customers, products, subscriptions, invoices, pre-auth & split payments

## Goal
Recurring revenue and advanced payment flows: **saved payment methods**, **subscriptions** with **invoices** and **dunning**, **pre-authorisation + capture**, and **split payments** (marketplace-style payouts to sub-merchants).

## Services / apps touched
- `backend/services/billing` (new)
- `payments` (customers, saved payment methods, off-session charges, manual capture, transfers)
- `ledger`, `payouts` (connected accounts / splits)
- `apps/checkout` (save card, subscription checkout), `apps/dashboard` (Customers, Products, Subscriptions, Invoices)

## Payments additions
```
customers(id 'cus_', merchant_id, livemode, email, name, phone, address JSONB, metadata, created_at)
payment_methods(id 'pm_', customer_id, type, card_brand, last4, exp_month, exp_year, fingerprint,
                acquirer_token, is_default, created_at)
setup_intents(id 'seti_', customer_id, status, ...)   -- save card without charging (3DS once)
```
- **Off-session** charge: `POST /v1/payment_intents { customer, payment_method, off_session: true }`. acquirer-sim test card `4000 0027 6000 3184` requires 3DS on every charge → subscription goes `past_due` with "authentication required" email.
- **Pre-auth / capture:** `capture_method=manual` → status `requires_capture`; `POST /capture { amount_to_capture ≤ authorised }`; auto-expire after 7 days (job) → `canceled`. Partial capture releases rest. Use cases: hotels, rentals.

## Billing model (`billing` DB)
```
products(id 'prod_', merchant_id, livemode, name, description, images, active)
prices(id 'price_', product_id, unit_amount, currency, type ENUM(one_time, recurring),
       interval ENUM(day, week, month, year), interval_count, trial_days, active)
subscriptions(id 'sub_', customer_id, status ENUM(trialing, active, past_due, canceled, unpaid, incomplete),
              current_period_start, current_period_end, cancel_at_period_end, default_payment_method,
              billing_cycle_anchor, created_at)
subscription_items(id, subscription_id, price_id, quantity)
invoices(id 'in_', subscription_id NULL, customer_id, number, status ENUM(draft, open, paid, void, uncollectible),
         subtotal, tax, total, amount_paid, currency, due_date, payment_intent_id, hosted_invoice_url, pdf_url, period_start, period_end)
invoice_lines(id, invoice_id, description, amount, quantity, price_id, proration BOOL, period_start, period_end)
dunning_attempts(invoice_id, attempt_no, scheduled_at, result)
```

## Subscription engine
- `BillingJob` (ShedLock, simulated clock): find subscriptions with `current_period_end ≤ now` → create invoice → finalize → create off-session payment intent.
- Consume `payment.events`: invoice paid → subscription `active`, advance period; failed → `past_due`, schedule retry (day 1, 3, 5, 7) → then `unpaid`/`canceled` per merchant settings.
- **Proration** on plan change (upgrade mid-cycle) — classic tricky calculation; unit-test thoroughly.
- Trials, cancel at period end, pause collection.
- Invoice PDF (OpenPDF) + hosted invoice page in checkout app (`/i/{id}`) — pay invoice by link.
- Also **one-off invoices** (merchant creates invoice, customer pays via link) — Paypercut "invoices" use case.

## Split payments
- `connected_accounts(id 'acct_', platform_merchant_id, name, iban, status)` in `identity`.
- Payment intent with `transfer_data: [{ destination: acct_…, amount }]` or `application_fee_amount`.
- Ledger posts to connected account balances; payouts pays them separately.
- Use case: marketplace/delivery platform paying restaurants.

## Kafka
- `subscription.events`: `SubscriptionCreated`, `SubscriptionRenewed`, `SubscriptionPastDue`, `SubscriptionCanceled`
- `invoice.events`: `InvoiceFinalized`, `InvoicePaid`, `InvoicePaymentFailed`
- `customer.events`
- billing consumes `payment.events`; ledger consumes transfers.

## REST endpoints
CRUD for `/v1/customers`, `/v1/customers/{id}/payment_methods`, `/v1/setup_intents`, `/v1/products`, `/v1/prices`, `/v1/subscriptions` (+ `/cancel`, `/pause`, `/resume`), `/v1/invoices` (+ `/finalize`, `/pay`, `/void`, `/pdf`), `/v1/payment_intents/{id}/capture`, `/v1/connected_accounts`.
Public: `/i/{invoice_id}`, `/checkout/subscription/{session}`.

## UI screens
- **Customers:** list, detail (payment methods, payments, subscriptions, invoices)
- **Product catalogue:** products & prices
- **Subscriptions:** list with MRR, detail (timeline, upcoming invoice preview, change plan with proration preview, cancel)
- **Invoices:** list, create one-off invoice editor, PDF preview
- **Payments:** "Uncaptured" filter + Capture/Release buttons
- **Connected accounts** page
- Home: MRR, churn charts
- Checkout: "Save card for future payments" checkbox, subscription summary (trial, then €X/month)

## Learning objectives
- Recurring billing engine & time-based state machines
- Proration math
- Dunning / retry strategies
- Authorisation vs capture
- Multi-party money flows in the ledger

## Acceptance criteria
- Subscribe with 7-day trial → advance clock 7 days → invoice paid → advance 1 month → renewed.
- Card that starts declining → past_due → retries → canceled after final attempt; emails logged.
- Upgrade mid-month: proration lines correct (unit tests with fixed dates).
- Pre-auth €200, capture €150 → ledger shows €150; uncaptured auth expires after 7 days.
- Split payment €100 → platform fee + connected account balance correct; trial balance = 0.
- Tests: proration unit tests, subscription lifecycle integration test, Playwright `subscription.spec.ts`, `preauth.spec.ts`.

## As built
Status: built. Design decisions and trade-offs are in [ADR 0015](../adr/0015-billing-saved-cards-and-preauthorisation.md) (pre-auth, saved cards, billing) and [ADR 0016](../adr/0016-split-payments.md) (split payments). Where the build differs from the plan above:

- **Pre-authorisation.** A new status `requires_capture`; `amount_authorized` and `capture_before` on the payment; `POST /capture` (all or part) and cancel as release; an hourly-style job (every 10 s, `JobLock`) releases holds after seven days on the payments service's simulated clock. The acquirer got `/capture` (idempotent for the same amount) and `/void`. Pre-auth cannot be combined with a split.
- **Customers and saved cards** live in `payments` (not billing) next to the vault tokens: `customers`, `payment_methods`, `setup_intents`; the hosted card page is `/s/{secret}`. `setup_future_usage` saves the card used at checkout. Off-session charges are `POST /v1/payment_intents { customer, off_session: true }`. Test cards `4000 0027 6000 3184` (authentication on every charge) and `4000 0000 0000 0341` (saves, then declines) were added.
- **Billing service** (`backend/services/billing`, port 8088). The schema follows the plan with these changes: `interval` is `billing_interval`; subscriptions carry the interval and an anchor with a period index (trial = -1); `pending_invoice_items` hold proration lines; `invoice_counters` number invoices; `billing_settings` holds the retry days and the final action; `notifications` records the emails.
- **Subscription engine.** One run every five seconds renews due subscriptions, then runs due retries. Proration is seconds-based, half up, with credit and charge as separate lines, previewable, and either joins the next invoice or is charged at once. Pause, resume, cancel now or at the period end, and a 24-hour expiry for subscriptions whose first invoice was never paid were built; "unpaid"/"cancel" after the last retry is the merchant's choice.
- **Invoices.** Draft, finalize, pay, void, delete a draft, PDF (OpenPDF), hosted page `/i/{token}` in the checkout app, and one-off invoices with a tax percentage and discounts.
- **Split payments.** `connected_accounts` live in `identity` (not as a separate `acct` service); `transfer_data` and `application_fee_amount` on the payment; the ledger credits each party in one balanced entry and refunds are taken back in proportion; payouts opens a bank account for each connected account from the IBAN in the creation event. `GET /v1/balance?connected_account=` reads an account's balance.
- **Events.** `customer.events`, `subscription.events` and `invoice.events` were added; the public types are in the OpenAPI document and the webhooks guide.
- **UI.** Customers, Products, Subscriptions (MRR, churn, retry rules), Invoices and Connected accounts pages, capture and release on the payment page, "Pre-authorise only" on the terminal, a split option in "Create test payment", and the recurring revenue cards on Home.
- **Customer credit.** A credit larger than the next invoice stays with the customer (`customer_credit` and its entries in billing). Renewals and finalized invoices spend it before the card is charged, voiding gives it back, and `GET /v1/credit_balances?customer=` and the customer page show it. Invoices carry `credit_applied`; `total` is what is left to pay.
- **Uncaptured and churn.** "Uncaptured only" is a toggle next to the status filter on Payments. The subscriptions page charts churn for the last six calendar months (of the subscriptions that existed when the month began, the share that ended in it).
- **Not built.** A tax engine, email delivery (recorded only; phase 8), paying credit out or adjusting it by hand, and churn that counts only paying subscribers month by month (the monthly figure includes trials).
- **Tests.** Unit: status table, proration with fixed dates, period boundaries (31st, leap days), IBAN. Integration (real Postgres and Kafka): capture and expiry, customers and setup intents, `BillingFlowIT` (29: trial, renewal, concurrent workers, dunning to cancel, retry success, first-invoice expiry, proration, invoices, hosted payment by event), split payments in payments/ledger/payouts/identity, and a 20-refund test that the books stay balanced. Playwright: `preauth.spec.ts`, `saved-card.spec.ts`, `subscription.spec.ts` and `split-payment.spec.ts`.
