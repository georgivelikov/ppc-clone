# 0037 — Unknown outcomes, and seeing the business

- **Status:** Accepted
- **Date:** 2026-10-22

## Context
Experiment 6 (Toxiproxy between payments and the acquirer) showed that a bank that is slow rather than down is the worst case: the customer is told the payment failed, and the bank charges the card anyway. Separately, the observability leftovers from ADR 0031 (business dashboards, request context in logs, a browser-started trace, a synthetic canary) were still open.

## Decision

### An answer that never came is not a "no"
`AcquirerClient` now classifies why a call failed. *Not sent* (the circuit was open, the connection was refused or timed out) and *answered with an error* mean the bank did not process the request, so the payment fails as before. *Sent, no answer* (a read timeout, a cut connection) is an **unknown outcome**: `AuthResult.Unavailable.outcomeUnknown`. In that case the payment is left `processing` rather than failed; the customer sees "we are confirming your payment"; a second confirm is refused as already in progress, which is what prevents paying twice; and the sweeper from ADR 0032 asks the bank by reference and settles the payment either way. This is the general rule for money: a timeout is an unknown, and unknowns are resolved by asking the system of record, never by guessing.

The checkout page gets the matching fix for the other half of the same problem: when the confirm call fails with a server error or a lost connection after the card was tokenised, it reads the payment before showing an error, and shows the real state if the payment has moved on. (Open item from experiment 4, now closed.)

### Seeing the business
- **Funnel, webhooks and buy-now-pay-later dashboard** (`business.json`): payments created, succeeded and failed with conversion; refunds and disputes; webhook delivery outcomes, first-try rate and merchants' response times; BNPL applications, approvals and provider trouble. They are built from two cheap sources: every outbox event is counted by type (`cutpay_events_total`), and the webhook delivery worker counts attempts by outcome and times them.
- **Alerts** for webhook deliveries that give up, a low payment conversion rate with real volume, and a failing or silent canary.
- **Who and what is in every log line.** A request interceptor puts `merchant_id` (from the verified token) and `payment_intent_id` (from the path, only if it looks like a payment id) into the log context, so a support question becomes a Loki query on a field, not a hunt for a trace id.
- **The browser starts the trace.** The checkout page sends a W3C `traceparent` on every call. Checked: a request through the gateway with a given trace id arrives in payments and in the outbox row with the same trace id, so one trace now spans the customer's click, the services and the events after it. Ids only, nothing about the customer or the card.
- **A canary.** One browser journey (sign up, pay on the hosted checkout, see the payment) that runs against any environment on a schedule, with a time budget on Pay-to-success, a result file and an optional push of metrics for the alerts.

## Not done
- No custom metrics for per-merchant or per-payment-method breakdown; the event type is the only dimension. Per-merchant labels would make the number of series grow with customers.
- Kafka consumers do not put `merchant_id` in the log context (the interceptor is for HTTP requests).
- The canary needs a scheduler and a Pushgateway outside this repository; the spec and rules are here.
- Other acquirer callers (saved-card charges, 3-D Secure completion, BLIK) still treat a read timeout as a failure.

## Consequences
- A payment can now sit in `processing` for up to the sweeper's delay (two minutes by default) after a bank timeout. That is the correct, honest state, and the customer is told so.
- `outcomeUnknown` is a judgement from the exception type; a client library change that wraps timeouts differently would turn unknowns back into failures. `requestMayHaveBeenProcessed` is a small, tested-by-experiment function for that reason.
