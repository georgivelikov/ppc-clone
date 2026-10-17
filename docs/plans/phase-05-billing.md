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
