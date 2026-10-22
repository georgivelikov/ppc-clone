# 0032 — Resilience, reconciliation and dead letters

- **Status:** Accepted
- **Date:** 2026-10-21

## Context
Every service already survives the easy failures: retries with backoff, a dead-letter topic per consumer, idempotent consumers, a circuit breaker round the acquirer. What was missing was proof (nobody had killed anything on purpose) and an independent check that the money adds up. The chaos experiments in `docs/chaos/` supplied the first, and found two real problems on the way.

## Decision

### Three accounts of the same money, compared every night
The acquirer simulator keeps a book of what it did (authorisations, captures, releases, refunds) and serves it as a CSV at `GET /settlement?date=`, as a real acquirer's settlement file would arrive. The reporting service holds the other two accounts as its read model: the payments service's view (`fact_payments`) and the ledger's (`fact_balance_transactions`). `Reconciliation` compares all three per payment and names each kind of difference: `missing_in_ledger`, `ledger_amount_differs`, `missing_at_acquirer`, `acquirer_amount_differs`, `refund_total_differs`, `charged_but_not_succeeded` and `unknown_payment`. A difference is reported only if it is still there after `settle-wait` (10 s), because every account is built from asynchronous events. Buy-now-pay-later payments are not in the acquirer file (a provider pays them), so only the ledger leg applies.

It runs at 02:00 UTC for the previous day, on demand from the back-office (Operations) and as `POST /v1/staff/reconciliation/runs`. The latest result is a Prometheus gauge, `cutpay_reconciliation_mismatches`, with an alert on it.

Without a date, only payments the read model first saw after the acquirer file begins are compared (`fact_payments.ingested_at`, the wall clock). The services run on simulated clocks in tests, so a payment's own timestamps cannot say this, and the simulator forgets everything when it restarts.

Reconciliation lives in the reporting service because that service already holds both internal accounts and can be rebuilt by replay. It writes nothing back; fixing is a human or an explicit tool (see below).

### Payments stuck in processing
Confirming a payment is two transactions with the card network between them: mark the payment `processing`, ask the network, record the answer. If the service dies or loses the database between the two, the customer is charged and the payment says `processing` for ever. Experiment 4 produced exactly this in two of three early runs. Two fixes:

- Reconciliation reports it (`charged_but_not_succeeded`) as soon as it happens.
- A job in payments (`resolveStuck`, every 10 s, for payments processing longer than `cutpay.payments.stuck-after`, two minutes) asks the acquirer what it did with that payment, by the reference the PSP sent (`GET /authorizations/by-reference/{id}`), and records the answer: charged means succeeded, no charge means failed, no answer means try again later. The acquirer is the source of truth because the money is there.

### Dead letters can be handled by a person
Each consumer already parks a record on `<topic>.dlt` after its retries. `reporting` now lists every `*.dlt` topic (`GET /v1/staff/dead_letters`) and an admin can send a record back to the topic it came from or discard it (`POST .../redrive`, `.../discard`); the decision is kept in `dead_letter_actions`, and the record itself stays on the dead-letter topic. Sending back is safe because every consumer deduplicates on the event id: those that already processed it skip it, the one that failed does the work. It only helps once the cause is fixed; sending an unfixable record back parks it again, so unfixable ones are discarded. Reading is for any staff member, acting for admins. Back-office page: Operations.

### Database connections
Fourteen services with the default ten-connection pools on one Postgres with `max_connections=100` ran out of connections as soon as they all warmed up, after which new connections, including the psql used to look, were refused. Pools now keep two idle connections and grow to ten, and the local Postgres allows 300.

## Consequences
- The acquirer file is in memory, like the rest of the simulator; reconciling across a simulator restart only covers what happened since.
- `redrive` sends to the original topic, so every consumer of that topic sees the record again. That is safe only because consumers are idempotent, which is already a rule here.
- A confirm can still answer 500 for a payment that went through (see experiment 4). The sweeper makes the payment right, and the checkout page now looks the payment up before showing an error (ADR 0037).
