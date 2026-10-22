# Chaos experiments

Six experiments, each a hypothesis, a fault injected while the platform is under load, and a verdict checked against the three accounts of the money (payments, ledger, acquirer settlement file; see ADR 0032). They are scripts, so they can be run again after any change:

```
docker compose -f infra/docker-compose.yml up -d
(cd backend && ./gradlew bootJar)
cd e2e
node chaos/stack.mjs up              # every service as a plain jar, so one can be killed (stop the containers first: node scripts/stop.mjs --keep-infra)
docker compose -f ../infra/docker-compose.yml --profile chaos up -d toxiproxy    # experiment 6 only
node chaos/experiments.mjs           # all six, or: node chaos/experiments.mjs 1 3
node chaos/stack.mjs down
```

The load goes around the gateway's per-address rate limit on the public checkout (10 requests a second, which is what it is for) and talks to the payments service and the card vault directly. Everything else, including the staff API, goes through the gateway.

Results below are from 2026-10-21 on a developer laptop. Durations are short on purpose; the plan's "ten minutes" would only make the lag larger, not different.

## 1. Kill the ledger during load
**Hypothesis.** Checkout is unaffected. The ledger's consumer lag grows while it is down and drains after it returns. Every payment is booked exactly once.

**Result: held.** 60 payments with the ledger down all succeeded (p95 265 ms). Lag reached 120 records in 5 s. After the restart the ledger drained them in about 35 s. 65 payments, 65 ledger lines, and reconciliation reported no differences.

## 2. The acquirer fails half of its requests
**Hypothesis.** The circuit breaker opens, customers get a clear failure quickly, nobody is charged twice, and the platform recovers by itself.

**Result: held, with a finding.** Of 60 payments, 7 succeeded and 53 failed with a `processing_error`, with a p95 under 100 ms: failures were fast and clear. Reconciliation found nothing charged twice and nothing charged but failed. Ten seconds after the acquirer recovered, 10 of 10 payments succeeded.

*Finding:* 88% failed with a 50% error rate, because once the breaker opens it rejects everything for ten seconds, including requests that would have worked. That is the breaker doing its job, and also the reason a real PSP routes to a second acquirer while the first is open. Not changed here.

## 3. Duplicated and reordered events
**Hypothesis.** The same event delivered three times books once. A refund that arrives before its payment waits (the consumer retries) and is booked once the payment arrives. The books still balance.

**Result: held.** A refund published before its payment was not booked. The payment, published three times with one event id, was booked once; the refund was booked once after it. The balance was the payment minus the refund.

## 4. Postgres restarts under load
**Hypothesis.** Requests during the restart fail cleanly, pools reconnect without restarting any service, consumers resume, nothing is lost or doubled.

**Result: held after two fixes.** Connection pools and consumers recovered by themselves. A handful of requests (4 or 5 of about 300) answered 500 during the second the database was away. The first runs found two problems, both fixed:

- *Payments stuck in `processing`.* A confirm that lost the database between asking the network and recording the answer left the customer charged and the payment `processing` for ever, and reconciliation did not look for it. Now reconciliation reports `charged_but_not_succeeded` and a sweeper in payments settles such payments from the acquirer's record (ADR 0032). The sweeper is tested in `PaymentsFlowIT`; the later runs of this script did not happen to hit the window, so the end-to-end path was exercised by the integration tests, not by the final script run.
- *Out of connections.* The first full start of fourteen services exhausted Postgres' 100 connections. Pool sizes and `max_connections` were set (ADR 0032).

*Closed later (ADR 0037):* a confirm can still answer 500 for a payment that then went through (seen in 3 of 4 in one run). The sweeper fixes the payment, and the checkout page now reads the payment before showing an error.

## 5. A poison message
**Hypothesis.** A record that cannot be processed does not block the topic. After its retries it lands on a dead-letter topic and shows in the back-office. Sending it back, once the cause is fixed, processes it exactly once.

**Result: held.** A malformed record and a refund for a payment that did not exist yet were published; five healthy payments behind them went through. After the retries (about 30 s) both appeared in Operations, on `refund.events.dlt`, `refund.events.webhooks.dlt` and others, one per consumer that gave up. After the payment was published, an admin sent the refund back and the ledger booked it once; the malformed record was discarded; both left the list.

## 6. The network between payments and the acquirer gets slow, then silent (Toxiproxy)
**Hypothesis.** A slow answer is cut off at the read timeout and the customer gets a clear answer. A payment the bank did process but whose answer was lost is settled by the sweeper and not left charged-but-failed. Silence charges nobody.

**Result: held after one fix.** Toxiproxy sits between payments and the acquirer simulator (`CUTPAY_ACQUIRER_URL` points at the proxy). Three faults, in turn:

- *Slow network (7 s on every answer, read timeout 5 s).* 12 of 12 payments answered `502 acquirer_unavailable` at 5.1 s. Nothing was charged: the delay hit the first call (reading the card token), before the bank saw an authorisation.
- *Silence (the connection opens, nothing comes back).* 8 of 8 the same, at 5.1 s.
- *A slow bank (the simulator takes 7 s to decide, then charges).* The dangerous one. The first version of this experiment **failed**: payments gave up at 5 s, marked the payments `failed` with `processing_error`, and the bank charged the cards seconds later. The nightly reconciliation caught it as 8 `charged_but_not_succeeded`, but the customers had been told they were not charged, would have retried and paid twice, and the sweeper (which only looks at payments still `processing`) never saw them.

*Fix* (ADR 0037): the acquirer client now tells apart "never sent" (circuit open, connection refused, connect timeout) and "the bank answered with an error" from "sent, and the answer never came" (read timeout, cut connection). Only the last is an *unknown outcome*; the payment then stays `processing`, the customer sees "we are confirming your payment", a second confirm is refused (so no double charge) and the sweeper asks the bank by reference. After the fix: the 8 customers were told "processing" at 5.1 s, the sweeper settled all 8 as `succeeded`, no payment was left processing, and reconciliation was clean (20 payments, 20 acquirer lines). `PaymentsFlowIT` has the same story as a test.

*Also seen:* after timeouts the circuit breaker opens, and the first payments after the network heals fail until it closes ten seconds later, as designed.

## Not covered
- Bandwidth limits, packet loss and partial partitions between other pairs of services (Toxiproxy can do them; only the acquirer link has a proxy).
- Other callers of the acquirer (saved-card charges, 3-D Secure completion, BLIK) still treat a read timeout as a failure; only the main confirm path keeps an unknown outcome processing.
- Debezium in place of the polling outbox relay: a separate slice.
- Postgres failover with a replica (Patroni or an operator): a restart is what is tested.
