# Phase 1 — Card payments core

## Goal
A customer pays with a **test card** on a **hosted checkout page**, including an optional **3DS challenge**. The merchant sees the payment in the dashboard and can **refund** it.

## Services / apps touched
- `backend/services/payments` (new)
- `backend/services/acquirer-sim` (new)
- `frontend/apps/checkout` (new)
- `frontend/apps/dashboard` (Payments pages)

## Payment state machine
```
requires_payment_method ─▶ processing ─▶ succeeded
          │                   │  └──▶ requires_action (3DS) ─▶ processing
          │                   └────▶ failed
          └──────────────▶ canceled
succeeded ─▶ partially_refunded ─▶ refunded
```
Implement as an explicit transition table (enum + allowed transitions) — reject illegal transitions with a domain exception. Optimistic locking (`@Version`).

## acquirer-sim (fake card network)
Test cards (any future expiry, any CVC):
| PAN | Behaviour |
|---|---|
| 4242 4242 4242 4242 | approve |
| 5555 5555 5555 4444 | approve (Mastercard) |
| 4000 0000 0000 3220 | 3DS challenge required |
| 4000 0000 0000 0002 | decline `card_declined` |
| 4000 0000 0000 9995 | decline `insufficient_funds` |
| 4000 0000 0000 0069 | decline `expired_card` |
| 4000 0000 0000 0119 | `processing_error` (5xx) |
| 4000 0000 0000 7777 | 10 s latency → timeout |

Endpoints: `POST /authorize`, `POST /capture`, `POST /refund`, `POST /3ds/challenge/{id}/complete`.
Card data **never** stored by `payments`: checkout posts PAN to `acquirer-sim /tokenize` → returns `tok_…` (mimics a PCI vault). Payments only stores brand, last4, exp, fingerprint.
Config: `latency-ms`, `failure-rate` via actuator/admin endpoint for chaos later.

## Data model (`payments` DB)
```
payment_intents(id 'pay_', merchant_id, livemode, amount, currency, status, capture_method ENUM(automatic, manual),
                description, customer_email, metadata JSONB, client_secret, return_url,
                amount_refunded, failure_code, failure_message, version, created_at, updated_at)
payment_attempts(id, payment_intent_id FK, method_type, card_brand, card_last4, card_fingerprint,
                 acquirer_ref, status, decline_code, three_ds_status, created_at)
refunds(id 're_', payment_intent_id FK, amount, reason, status, acquirer_ref, created_at)
idempotency_keys(merchant_id, key, request_hash, response_status, response_body, created_at, PK(merchant_id, key))
outbox(...), processed_events(...)
```

## REST endpoints
| Method | Path | Caller |
|---|---|---|
| POST | `/v1/payment_intents` | merchant (dashboard for now) |
| GET | `/v1/payment_intents`, `/v1/payment_intents/{id}` | merchant |
| POST | `/v1/payment_intents/{id}/cancel` | merchant |
| GET | `/checkout/sessions/{client_secret}` | hosted checkout (public) |
| POST | `/checkout/sessions/{client_secret}/confirm` | hosted checkout (`{ token }`) |
| POST | `/v1/refunds` | merchant |
| GET | `/v1/refunds` | merchant |

Also a dashboard-only `POST /v1/checkout_sessions` that creates an intent and returns the hosted URL `http://localhost:5174/c/{client_secret}`.

## Kafka
`payment.events` (key `pay_…`): `PaymentIntentCreated`, `PaymentRequiresAction`, `PaymentSucceeded`, `PaymentFailed`, `PaymentCanceled`.
`refund.events`: `RefundCreated`, `RefundSucceeded`, `RefundFailed`.
No consumers yet besides a debug logger (ledger arrives in phase 2).

## UI screens
**Checkout app** (`apps/checkout`, public, mobile-first):
- Order summary (merchant name/logo, amount, description), email field, card form (number with brand detection + Luhn, expiry, CVC), Pay button with loading state
- 3DS challenge page (fake bank: "Enter code 1234" + Approve/Fail buttons)
- Success / failure screens, redirect to `return_url`

**Dashboard**:
- Payments list (filters: status, date, amount; search by id/email; cursor pagination)
- Payment detail: timeline of events, card info, refund button + modal (full/partial), raw JSON panel
- "Create test payment" button → opens hosted checkout in new tab
- Home: today's volume & count (simple query)

## Tasks
1. `acquirer-sim` service with test-card table, tokenization, configurable latency.
2. `payments` domain: entities, state machine, `Money` usage, Flyway.
3. Idempotency filter (`Idempotency-Key`) — store request hash + response; return stored response on replay; 409 on hash mismatch.
4. Confirm flow: call acquirer via `RestClient` with timeout + Resilience4j circuit breaker; persist attempt; transition; write outbox in same transaction.
5. 3DS: acquirer returns `requires_action` + `redirect_url`; checkout shows challenge; on completion acquirer calls back payments (`/internal/3ds/callback`).
6. Refunds: validate `amount ≤ amount − amount_refunded`; call acquirer; update status.
7. Checkout app: Vite + React, form validation (react-hook-form + zod), card brand icons.
8. Dashboard pages + API client.
9. Gateway routes; public routes for checkout with rate limiting.

## Learning objectives
- Aggregate design & state machines
- Idempotency keys done right
- Timeouts, retries, circuit breakers on outbound calls
- Tokenisation / PCI scope minimisation (conceptually)

## Acceptance criteria
- Paying with 4242… → succeeded; with …0002 → failed with readable message; with …3220 → 3DS → succeeded.
- Same `Idempotency-Key` twice → one payment.
- Refund 50% then 50% → `refunded`; a third refund → 400.
- Tests: state-machine unit tests (all transitions), payments integration tests with Testcontainers + WireMock for acquirer, Playwright `card-payment.spec.ts` (happy, decline, 3DS, refund).

## Open questions
- Should card form be an iframe from day one (like a real PSP) or plain form now and iframe in phase 3? (Default: plain now, iframe in phase 3.)
