# 0021 — BNPL resilience, cascade and learning

- **Status:** Accepted
- **Date:** 2026-10-20

## Context
Providers fail in three ways: they are slow, they are down, and they say no. The first two should cost the customer nothing; the third should cost them only a better offer. The router also has no way yet to notice that a provider it likes has stopped approving people.

## Decision

### A circuit per provider
Every adapter sits behind a Resilience4j circuit breaker (`ProtectedAdapter`), one per provider, shared by quotes, applications, polls, cancels and refunds: an outage seen while quoting protects the application that would follow. A call counts as a failure when it times out, the provider is down or the answer cannot be read; a provider that answers and refuses (an amount outside its limits, a refund too large) is working and never counts, and nor does a callback we reject. With the defaults, five calls in a window of ten with half failing open the circuit for ten seconds. An open circuit refuses calls at once, as an ordinary "unavailable" failure, and eligibility leaves the provider out of the offers with the reason "temporarily unavailable", so no request waits for it. The circuit moves to half open by itself when the wait is over and the next request is the probe: success closes it, failure opens it again. All of it is configurable (`cutpay.bnpl.breaker.*`).

### The next best provider, with consent
`POST /checkout/{secret}/bnpl/apply_best` applies with the provider the routing picks. With `fallback: true` the customer has agreed that, after a decline or a provider that could not be reached, the next best provider is tried, at most two more times (three in all). A provider that asks for details the customer has not given is skipped. Each try is an application of its own and keeps its outcome, and a declined or failed application does not block the next, so a customer is never financed twice. A provider that decides later (Lendo, KreditKo) ends the cascade at once with a pending application: waiting for it is the customer's choice, not a failure.

### What the router knows
`RollingStats` measures each provider from its decided applications of the last seven days: the share approved and the median time to decide. With little history it leans on stated priors, as if the provider had been seen ten times behaving as expected, so one early decline does not bury a provider and a long record decides. A timeout or a failure is not a decline and does not lower the approval rate; the circuit deals with those. The figures are a query over the applications table, not a windowed aggregation over the event stream: they are the same numbers, the table is already there, and the analytics page reads it too. They are platform-wide, since a provider behaves the same for every merchant.

### Analytics for the merchant
`GET /v1/bnpl/analytics?days=` returns, per provider and for the merchant only: applications, approved, declined, failed, pending and abandoned, approval rate (of decided ones), average and median time to decide, financed volume, fees paid, and the state of the circuit now.

## Consequences
- A provider that is down for a minute drops out of the offers after about five failed quotes and comes back by itself.
- Circuit state is per instance, in memory. Several instances of `bnpl` each learn on their own.
- Routing depends on figures that change with traffic, so tests that assert on rankings start from an empty history and closed circuits.
- Kafka Streams was left out on purpose; see above.
