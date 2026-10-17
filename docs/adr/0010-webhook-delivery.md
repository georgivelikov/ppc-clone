# 0010 — How webhooks are delivered

- **Status:** Accepted
- **Date:** 2026-10-17

## Context
Merchants receive events (`payment_intent.succeeded`, `payout.paid`, …) as signed HTTP calls. Delivery must be at-least-once, survive crashes and slow receivers, retry for days, and be inspectable (a delivery log, manual resend). The plan offered two ways to build the retry machinery.

## Options
1. **A queue in Postgres.** One row per (event, endpoint) with `next_attempt_at`; workers poll for due rows.
2. **Kafka retry topics** (`webhook.deliveries.retry.N`), one topic per delay step.

## Decision
**Postgres queue.**
- Workers claim due rows with `SELECT … FOR UPDATE SKIP LOCKED` and push `next_attempt_at` out by a lease (two minutes) in the same transaction. The HTTP call happens outside any transaction. Several instances can poll at once without sending twice, and a worker that dies mid-send only delays that delivery by the lease. No `JobLock` is needed.
- Every attempt is stored (`delivery_attempts`) with the response code, a 500-character body snippet, the error and the duration. That *is* the delivery log in the dashboard, and a manual resend is one `UPDATE` that makes the row due now.
- Retry delays: 1 min, 5 min, 30 min, 2 h, 5 h, then every 10 h, until the event is three days old. Due times use the injectable clock, so tests move time with the simulated clock instead of waiting.
- An endpoint that has failed continuously for three days (`failing_since`, cleared by any success) is disabled; its waiting deliveries stop, and the merchant can re-enable it and resend by hand. Email notification arrives in phase 8.
- Ingest is exactly-once in effect: the Kafka handler records the internal event in `processed_events`, creates the public event (which reuses the internal event id) and queues the deliveries in one transaction.

## Why not Kafka retry topics
They suit stateless retry well, but a merchant needs to *see* a delivery's history and resend one on demand, which means a database table anyway. Delays longer than a few minutes need either many topics or consumers that sleep, and a sleeping consumer blocks its partition. A table gives per-delivery state, ordering by due time and cheap manual control; the cost is polling load, which is negligible at this scale and can be reduced later with `LISTEN/NOTIFY`.

## Other decisions
- **Signature:** `Cutpay-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`. The timestamp is signed, and receivers reject anything older than five minutes (or in the future), which stops replay. A header may carry several `v1` values so secrets can be rotated. The code lives in `libs/webhook-signature`, free of dependencies, so the demo shop can reuse it.
- **Same body on every retry**, and `Cutpay-Event-Id` on every request, so receivers can deduplicate.
- **Only 2xx is success.** Redirects are failures; the client never follows them.
- **Public events are a separate contract.** `EventMapper` copies only chosen fields from internal events, so internal payloads can change freely.
- **URLs:** test mode allows `http` and localhost (a developer machine is where receivers run). Live mode needs `https` and refuses internal addresses, checked when the endpoint is saved and again at delivery after DNS resolution.
- **Secrets** are shown once, on creation, and stored as given (they must be readable to sign). Hashing is not possible here, unlike API keys.

## Consequences
- Delivery order across events is not guaranteed; receivers must tolerate it (events carry `created_at`).
- A receiver that takes the full 10 s timeout occupies one worker thread; sends run on virtual threads, so this costs little.
- Deleting an endpoint is a soft delete, because past deliveries point at it.
