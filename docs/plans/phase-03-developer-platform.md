# Phase 3 — Developer platform (API keys, sandbox, webhooks, SDK, demo shop, docs)

## Goal
A third-party shop integrates Cutpay the way a real merchant would integrate Paypercut: **API keys**, **test/live modes**, a **public REST API**, an **embeddable checkout SDK**, **signed webhooks**, and **documentation**.

## Services / apps touched
- `identity` (API keys)
- `gateway` (API-key auth, per-key rate limits, request logs)
- `backend/services/webhooks` (new)
- `frontend/packages/checkout-js` (new), `frontend/apps/demo-shop` (new), `frontend/apps/site` (new: marketing + docs)
- `apps/checkout` (iframe card fields), `apps/dashboard` (Developers section)

## API keys
```
api_keys(id 'key_', merchant_id, livemode, type ENUM(secret, publishable, restricted), prefix,
         key_hash /* SHA-256 */, last4, scopes TEXT[], created_by, last_used_at, revoked_at, expires_at)
```
- Formats: `sk_test_…`, `sk_live_…`, `pk_test_…`, `pk_live_…`, `rk_…` (restricted, scoped e.g. `payments:read`).
- Secret shown **once** on creation. Roll key with optional grace period (old key valid N hours).
- Gateway: `Authorization: Bearer sk_…` → lookup by prefix + hash (cache in Redis) → inject merchant/mode headers.
- Publishable keys only allowed on checkout/tokenize endpoints.

## Request log
Gateway emits `api.requests` topic (method, path, status, latency, key id, idempotency key, request/response bodies redacted). A small consumer in `webhooks` (or a new `devlogs` module) stores last 30 days → **Developers → Logs** page.

## Webhooks service
```
webhook_endpoints(id 'we_', merchant_id, livemode, url, secret 'whsec_…', enabled_events TEXT[], status, created_at)
events(id 'evt_', merchant_id, livemode, type, api_version, data JSONB, created_at)   -- public event objects
webhook_deliveries(id 'whd_', event_id, endpoint_id, attempt, status, response_code, response_body_snippet,
                   duration_ms, next_attempt_at, created_at)
```
- Consumes all public-facing topics (`payment.events`, `refund.events`, `payout.events`, …), maps to public event types (`payment_intent.succeeded`, `charge.refunded`, `payout.paid`, …).
- Signature header: `Cutpay-Signature: t=<ts>,v1=<HMAC-SHA256(secret, ts + "." + body)>`.
- Retries with exponential backoff (1m, 5m, 30m, 2h, 5h, 10h… up to 3 days) — implement using a DB queue polled by workers, **or** Kafka retry topics (`webhook.deliveries.retry.N`). Pick one and document trade-offs in an ADR.
- Endpoint auto-disabled after N consecutive days of failures; email in phase 8.
- Endpoints: CRUD `/v1/webhook_endpoints`, `GET /v1/events`, `POST /v1/webhook_endpoints/{id}/test`, `POST /v1/webhook_deliveries/{id}/retry`.
- `cutpay listen`-style dev helper is optional: a tiny Java/Node CLI that forwards webhooks to localhost.

## Public API hardening
- Expand-able fields (`?expand[]=latest_attempt`), metadata on all objects, consistent list endpoints.
- `Cutpay-Version` header (date-based API versioning, one version for now but wire the mechanism).
- Aggregate OpenAPI at gateway → `docs/api/openapi.yaml` → generate TS client (`openapi-typescript`) and Java client (optional).

## checkout-js SDK
```ts
const cutpay = Cutpay('pk_test_…');
const elements = cutpay.elements({ clientSecret });
elements.create('card').mount('#card');
const { error, paymentIntent } = await cutpay.confirmPayment({ elements, returnUrl });
```
- Card fields rendered in **iframes** served by `apps/checkout` (`/elements/card`) → PAN never touches merchant page. Communication via `postMessage` with origin checks.
- Also `cutpay.redirectToCheckout({ sessionId })` for hosted mode.
- Build as ESM + UMD with Vite library mode, served from `http://localhost:5174/v1/cutpay.js`.

## Demo shop (`apps/demo-shop`)
- Tiny React store (3 products, cart) + a **small Node/Express or Spring Boot backend** holding the secret key (shows correct server-side integration).
- Both flows: embedded (SDK) and hosted redirect.
- Webhook receiver verifying signatures → marks order paid; shows "orders" page.

## Site (`apps/site`)
- Marketing pages mirroring Paypercut's structure: Home, Products (Checkout, Links & QR, Billing & Payouts, BNPL, Virtual POS), Pricing, Business types, FAQ. Own copy & design.
- Docs: Getting started, Test cards, Webhooks guide, API reference (Redoc rendering the OpenAPI).

## Dashboard — Developers section
- API keys (create, reveal-once, roll, revoke), Webhooks (endpoints, event types picker, deliveries log, resend), Events, Logs.
- Test-mode banner across the app; data filtered by mode.

## Tasks
1. API key storage, generation (SecureRandom, base62), hashing, gateway auth filter.
2. Mode separation audit: every query filters by `livemode`.
3. Request log pipeline.
4. Webhooks service + retry mechanism + signing lib (also used by demo shop).
5. Iframe card element + SDK + postMessage protocol.
6. Demo shop + backend.
7. Site + docs + Redoc.
8. Dashboard Developers pages.

## Learning objectives
- API key design, hashing, scoping, rotation
- Reliable outbound webhooks (at-least-once, signatures, replay protection)
- Cross-origin iframes & postMessage security
- API design & versioning, OpenAPI-driven clients

## Acceptance criteria
- Demo shop buys a product with `pk_test`/`sk_test` keys; webhook marks order paid.
- Test-mode data never visible in live mode and vice-versa.
- Webhook endpoint returning 500 is retried per schedule (verify with simulated clock); manual resend works.
- Signature verification fails on tampered body or old timestamp (>5 min).
- Tests: gateway auth tests, signature lib tests, Playwright `demo-shop.spec.ts` (embedded + hosted).
