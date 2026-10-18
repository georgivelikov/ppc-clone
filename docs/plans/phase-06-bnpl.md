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

## As built
Status: built. Design decisions and trade-offs are in [ADR 0017](../adr/0017-bnpl-provider-simulator.md) (the simulator), [ADR 0018](../adr/0018-bnpl-aggregation-and-routing.md) (adapters, eligibility, ranking), [ADR 0019](../adr/0019-bnpl-applications-and-settlement-of-the-payment.md) (applications and how they settle a payment), [ADR 0020](../adr/0020-bnpl-settlement-and-refunds.md) (settlement and refunds) and [ADR 0021](../adr/0021-bnpl-resilience-cascade-and-learning.md) (circuit breakers, cascade, learning). Where the build differs from the plan above:

- **Providers (`bnpl-sim`, port 8089).** The four providers have the shapes of the plan: SplitNow (REST, cents, instant, blocklist), Lendo (form-encoded, decimal euros, a signed callback seconds later, score by birthdate), KreditKo (camelCase, national ID and income, polled, manual review above EUR 5,000) and PayLaterEU (one RPC endpoint that always answers 200, 20% of calls hang). Outage, latency, timeout rate and decision delay are changed at runtime through `PUT /admin/providers/{code}` or a small page at the simulator's root.
- **Domain (`bnpl`, port 8091).** There is no `bnpl_providers` table: the catalogue (limits, currencies, countries) is code, and the merchant's choices are `merchant_bnpl_settings` and `merchant_routing`. Applications have the statuses `created`, `pending_review`, `approved`, `declined`, `failed`, `expired`, `canceled` and `settled`; `pending_customer` was not needed because the checkout asks for what a provider needs (it is in each offer) before applying. `routing_decisions` keeps the whole decision as JSON. There is no `provider_stats` table: the figures are a query over the applications of the last week (see below). Refunds have a table of their own.
- **Routing.** Eligibility (merchant, currency, country, limits, circuit) runs before anything is called; ranking is `customer_choice`, `cheapest`, `fastest`, `best_approval` or `weighted`; every decision and its explanation is stored and shown on the payment. Approval rate and median decision time come from real decisions blended with priors. The epsilon-greedy mode and Kafka Streams were not built.
- **Offers and the acceptance text.** Providers are asked in parallel on virtual threads against a two-second deadline; a late provider is missing from the list and a cached or estimated quote was not needed. PayLaterEU quotes EUR 300 (its limit is EUR 20 to 800, as in the table above); it is left out from EUR 800.01. "Cheapest" for EUR 300 picks PayLaterEU (2.5%), and switching it off picks Lendo (2.9%), with the explanation changing accordingly.
- **Resilience.** One circuit breaker per provider around every call; an open circuit leaves the provider out of offers and applications, goes half open by itself and is closed by a probe. After a decline or a failure the next best provider is tried when the customer agreed, at most two more.
- **Flows.** Apply (`/checkout/{secret}/bnpl/apply` for a chosen plan, `apply_best` for Cutpay's choice), callback (`/bnpl/callback/{provider}`, verified by the adapter), polling and expiry. The payment is completed by `BnplApproved` on `bnpl.events`; an approval that arrives after the customer paid another way is refused and the application is cancelled at the provider. Settlement is simulated a day after approval. Refunds ask the provider, which returns its fee in proportion.
- **Events.** `bnpl.events` carries `BnplApplicationCreated`, `BnplApproved`, `BnplDeclined`, `BnplProviderTimedOut`, `BnplProviderFailed`, `BnplExpired`, `BnplCanceled`, `BnplSettled` and `BnplRefunded`. `BnplOffersQuoted` was not published: the stored decision says more.
- **Ledger.** A BNPL payment debits `bnpl_receivable` and credits the merchant (price less fee) at once; settlement moves the receivable to `customer_funds`; refunds take the merchant's share of the price. The trial balance stays zero throughout.
- **UI.** The checkout shows a "Pay in instalments" tab next to the card form (plans with what is paid, when and what it costs; the details a provider needs; waiting for a provider that decides later; "Best option" with the consent to try the next one). The dashboard has an Instalments page (routing mode and weights, providers on or off and in order with buttons rather than drag and drop, analytics per provider with the circuit state, and the applications) and a card on the payment that says who financed it and why. A docs page describes it.
- **Not built.** Kafka Streams, the epsilon-greedy bandit, drag and drop, per-merchant provider credentials, instalments for held or split payments, and working out the customer's country (it defaults to the platform's, or comes as a parameter).
- **Tests.** Unit: routing (table-driven eligibility, five modes, explanations), blending of statistics, what counts against a circuit. Contract (WireMock): each adapter's quotes, applications, status, cancel, refund and Lendo's signature. Integration (real Postgres and Kafka): offers and settings, applications through all three ways of answering, failures, cascade, learning, analytics, circuit lifecycle, refunds, settlement, and in payments and the ledger the approval, refusal, refunds and receivable. Playwright: `bnpl.spec.ts`.
