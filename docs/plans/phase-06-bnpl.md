# Phase 6 — BNPL aggregator & smart routing

## Goal
Paypercut's signature feature: **one integration, many Buy-Now-Pay-Later providers**. The customer picks instalments at checkout; either they choose a provider or Cutpay's **routing engine** picks the cheapest / fastest / most-likely-to-approve one. The merchant is **paid upfront**; the provider collects instalments.

## Services / apps touched
- `backend/services/bnpl` (new — aggregator, routing, provider adapters)
- `backend/services/bnpl-sim` (new — 4 fake providers in one app, each on its own path & config)
- `payments` (new payment method type `bnpl`)
- `ledger`, `payouts` (upfront settlement from provider, provider fees)
- `apps/checkout` (BNPL step), `apps/dashboard` (BNPL settings & analytics)

## Fake providers (`bnpl-sim`) — fictional names
| Provider | Plans | Merchant fee | Limits | Behaviour |
|---|---|---|---|---|
| **SplitNow** | 3 / 4 instalments, 0% | 3.5% | €50–€1,500 | fast (≤1 s), approves if email not on blocklist |
| **Lendo** | 6 / 12 / 24 months | 2.9% + interest to customer | €100–€10,000 | slow (5–20 s, async callback), credit score by birthdate |
| **KreditKo** | 3–36 months | 4.5% | €200–€15,000 | requires customer ID & income form, manual-review state |
| **PayLaterEU** | Pay in 30 days | 2.5% | €20–€800 | flaky: 20% timeouts |

Each provider exposes a deliberately **different API shape** (REST/JSON, form-encoded, async callback, polling) so adapters actually matter. Configurable approval rules + latencies + outage switch.

## Domain
```
bnpl_providers(id, code, name, enabled_global, supported_countries, supported_currencies)
merchant_bnpl_settings(merchant_id, provider_id, enabled, credentials JSONB (fake), priority, routing_mode ENUM(customer_choice, cheapest, fastest, best_approval))
bnpl_applications(id 'bnpl_', payment_intent_id, provider_id, plan_code, status ENUM(created, pending_customer,
                  pending_review, approved, declined, expired, canceled, settled), provider_ref, decision_ms,
                  quoted_fee, apr, instalments JSONB, created_at)
routing_decisions(id, payment_intent_id, candidates JSONB, chosen_provider, strategy, reason, created_at)
provider_stats(provider_id, window_start, attempts, approvals, avg_decision_ms, timeouts)   -- rolling, fed by events
```

## Routing engine
1. **Eligibility filter:** merchant enabled? amount within limits? currency/country? provider healthy (circuit breaker state)?
2. **Scoring** per strategy:
   - cheapest → minimise merchant fee
   - fastest → minimise p50 decision time
   - best_approval → maximise rolling approval rate (from `provider_stats`)
   - weighted → configurable weights
3. **Fallback / cascade:** on decline or timeout, try next candidate (if customer consented) — max 2 hops.
4. Persist `routing_decisions` for explainability (dashboard shows *why*).
5. Optional: multi-armed bandit (epsilon-greedy) mode — a fun ML-ish extension.

Adapters: `BnplProviderAdapter` interface (`quote`, `createApplication`, `getStatus`, `handleCallback`, `cancel`, `refund`). One implementation per fake provider. Resilience4j per adapter (timeout, circuit breaker, bulkhead).

## Flows
1. Checkout shows "Pay in instalments" → `GET /checkout/{secret}/bnpl/offers` → quotes from eligible providers (parallel calls with `CompletableFuture`, overall 2 s deadline, partial results OK).
2. Customer picks offer (or "Best option") → `POST …/bnpl/apply` → provider redirect/form/iframe → callback → `BnplApproved` → payment `succeeded`.
3. Provider settles to Cutpay (simulated next day) → ledger: provider receivable cleared; merchant gets **full amount minus BNPL fee** in normal payout (upfront payout).
4. Refunds → `adapter.refund` → provider adjusts instalments.

## Kafka
- `bnpl.events`: `BnplOffersQuoted`, `BnplApplicationCreated`, `BnplApproved`, `BnplDeclined`, `BnplProviderTimedOut`, `BnplSettled`
- `bnpl` consumes its own events to update `provider_stats` (stream aggregation). **Optional:** do this with **Kafka Streams** (windowed aggregation) — good Kafka Streams learning.
- payments consumes `BnplApproved/Declined`.

## REST endpoints
- Merchant: `GET/PUT /v1/bnpl/settings`, `GET /v1/bnpl/applications`, `GET /v1/bnpl/routing_decisions/{payment_intent}`, `GET /v1/bnpl/analytics`
- Public checkout: `/checkout/{secret}/bnpl/offers`, `/checkout/{secret}/bnpl/apply`, `/bnpl/callback/{provider}`

## UI screens
- **Checkout:** payment method tabs (Card | Pay in instalments); offer cards (provider, monthly amount, # instalments, APR, total cost); provider-specific steps (form for KreditKo, waiting screen for Lendo with polling/SSE)
- **Dashboard → BNPL:** providers toggle + priority drag-and-drop, routing mode selector, analytics (approval rate per provider, avg decision time, fees paid, volume), application list, routing decision explainer ("Chose SplitNow: cheapest eligible (3.5% vs 4.5%); Lendo excluded: amount < €100")
- **bnpl-sim admin UI** (tiny page): toggle outages, latency, approval rate — for demos

## Learning objectives
- Adapter/strategy patterns, anti-corruption layer
- Parallel fan-out with deadlines and partial results
- Circuit breakers and fallbacks per provider
- Async callback flows & correlation
- Kafka Streams windowed aggregation
- Receivables in the ledger

## Acceptance criteria
- €300 order: offers from SplitNow, Lendo, KreditKo (PayLaterEU excluded by limit) shown in < 2.5 s even if Lendo is slow (quote cached/estimated).
- "cheapest" routing picks expected provider; switching SplitNow off changes decision and explainer text.
- PayLaterEU outage → circuit opens → excluded from offers; recovers after half-open probe.
- Approved BNPL → merchant balance = amount − BNPL fee; trial balance = 0.
- Tests: routing engine unit tests (table-driven), adapter contract tests (WireMock), Playwright `bnpl.spec.ts`.
