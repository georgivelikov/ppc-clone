# 0018 — BNPL aggregation and routing

- **Status:** Accepted
- **Date:** 2026-10-19

## Context
One checkout has to offer instalments from several providers that disagree on units, field names and how they answer ([ADR 0017](0017-bnpl-provider-simulator.md)). The customer is waiting while this happens. The merchant wants to say how the provider is picked, and later to be told why one was.

## Decision

### One adapter per provider, in Cutpay's words
The `bnpl` service (port 8091, its own database) talks to providers only through `BnplProviderAdapter`. Everything that comes back is an `Offer` in minor units with the plan, the instalments, the interval, the APR, the total payable and the merchant fee. Decimal euros, cents, camelCase and "always HTTP 200 with `ok: false`" stop at the adapter. Every failure is a `ProviderException` of one of four kinds (timeout, unavailable, refused, unreadable), decided in one place (`ProviderHttp`), so the rest of the service never looks at an HTTP status. The adapters are tested against stand-ins that answer exactly as `bnpl-sim` does.

### Ask only who could say yes, ask them together, stop waiting at the deadline
`RoutingEngine.eligible` runs first and needs no network: the merchant has the provider on, it works in this currency and country, the amount is inside its limits, and it is not known to be down. Only those are called. They are called in parallel on virtual threads and collected against one deadline (2 s by default, with each call allowed 1.8 s). A provider that is late, down or refuses is left out of the answer with a sentence saying why; the customer gets the rest. KreditKo prices one term per call, so its adapter fans out again inside its own call.

### Ranking is a pure function
`RoutingEngine.decide` ranks the providers that answered. A provider is judged by its cheapest offer for the merchant. The modes are `customer_choice` (the merchant's own order), `cheapest` (lowest merchant fee), `fastest` (lowest median decision time), `best_approval` (highest approval rate) and `weighted` (cost, speed and approval scaled to 0..1 across the candidates and mixed by the merchant's weights). Ties fall back to the merchant's order, then to the catalogue order. Until real history exists, approval and speed come from stated priors (`PriorStats`); the same interface will serve measured figures.

### Priority is a position
A merchant's order is 0 to 3. Giving a provider a priority puts it at that position and the others keep their relative order around it. Two providers cannot be given the same position. This avoids the trap of explicit numbers colliding with defaults.

### Every decision is kept and explained
Each time offers are computed, the decision is stored with the ranked candidates, their offers and the providers left out, together with a sentence such as "Chose Lendo: cheapest eligible (2.9% vs 3.5% for SplitNow); PayLaterEU excluded: switched off in your settings". The merchant reads the latest one for a payment. The customer never sees the merchant fee, the ranking or why someone was excluded, only which providers could not be reached.

## Consequences
- A new provider is one adapter, one catalogue entry and one priors entry.
- Offers are quoted fresh on every request and not cached; a cache would make a slow provider less visible but would show stale prices.
- The acceptance text of the plan says PayLaterEU is excluded for a EUR 300 order, but its stated limit is EUR 20 to 800, so it quotes for 300 and is excluded from 800.01.
- Countries come from a request parameter and default to the platform setting; the checkout does not know the customer's country yet.
