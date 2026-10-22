# Performance

Three load scenarios, a tuning log, and an honest account of what one laptop can do. Numbers are from 2026-10-22 on a developer laptop that was also running Docker (Postgres, Kafka, Redis, the observability stack), fourteen JVMs started as plain jars, a browser and the load generator itself. Treat them as a way to find bottlenecks and to compare before and after, not as a benchmark of the design.

## Running them

```
docker compose -f infra/docker-compose.yml up -d
(cd backend && ./gradlew bootJar)
(cd e2e && node chaos/stack.mjs up)          # the jar stack, as for the chaos experiments

# checkout confirms at a fixed arrival rate (create session, tokenize, confirm)
docker run --rm -v "$PWD/perf/k6:/s" -e RATE=80 -e DURATION=60s grafana/k6 run /s/checkout.js
# merchant API reads through the gateway (list, get, stats); seeds payments first
docker run --rm -v "$PWD/perf/k6:/s" -e RATE=300 -e DURATION=60s grafana/k6 run /s/reads.js
# webhook fan-out: a burst of payments, N endpoints, time until everything has been delivered
node perf/webhook-burst.mjs 300 3
# restart payments with different settings, warm up, run checkout:  perf/variant.sh 100 SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=25
```

k6 runs in a container (`host.docker.internal`). Checkout goes straight to the card vault and the payments service because the gateway rate-limits the public checkout per address (10 a second, which is what that limit is for). Warm the JVMs first: a cold start is several times slower.

Stop the stack before running `./gradlew test` or the Playwright suite (see `docs/chaos/`). Also reset consumer offsets after a load run, or the integration tests wait behind the backlog the load left on the shared Kafka:

```
docker exec cutpay-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ledger --reset-offsets --to-latest --all-topics --execute     # and notifications, webhooks, reporting, bnpl
```

## Results

| Scenario | Target in the plan | Measured here | Verdict |
|---|---|---|---|
| Checkout confirms | 200/s | **~80/s** within p95 < 300 ms for the confirm; 90/s p95 about 1.1 s; 120/s collapses (p95 3 s+, iterations dropped) | not met on this machine; see below |
| API reads | 1,000/s | 110/s before tuning, **~300/s** after (the list call was the cost) | not met |
| Webhook burst | not stated | 300 payments x 3 endpoints = 900 deliveries, all delivered 5 s after the last payment | fine |

### Checkout: where the ceiling is
Throughput climbs to about 80 payments a second and then latency grows without bound (an open-loop test keeps arriving at the set rate, so the queue is the latency). At the knee the machine's CPU was at about 85%, the payments service used under two cores, and its connection pool (10) was full with a long queue in front of it, with every connection "idle in transaction" waiting on the application, not on Postgres. That is a CPU-starved machine, not a locking problem: no lock waits in `pg_stat_activity`, and Postgres itself was not busy. A payment costs about 11 transactions and ten new rows in the payments database (the payment, the attempt, two outbox rows, status changes), which is what the design asks for.

Variants tried against payments at 100 payments/s (each after a warm-up; the machine is noisy, so only large differences count):

| Variant | confirm p95 | iterations/s |
|---|---|---|
| pool 10 (default) | 5.9 s | 65 |
| pool 25 | 2.7 s | 90 |
| virtual threads, pool 25 | 5.6 s | 68 |

A bigger pool helps a little; virtual threads do not (the work is CPU-bound, not waiting on I/O). Neither is a clear win, so the defaults stay at 10. The honest summary: 200 a second needs more than this machine; the way to get there is more instances behind the gateway (every service is stateless, the work is partitioned by merchant) and a database that is not sharing a laptop.

### Reads: an N+1
The payment list loaded each payment's attempts with its own query: 21 queries for a page of 20. Now one query for the page (`findByPaymentIntentIdInOrderByCreatedAtAsc`). 110/s became about 300/s. Past that the pool is full and requests wait for a connection (average hold 75 ms, again a CPU-starved machine), and the remaining cost is Hibernate loading wide payment entities. A projection for the list (only the columns the list shows) is the next step if this matters.

### Behind the checkout: the event consumers
The load left a backlog (tens of thousands of events), which is the more interesting finding. Producers outran consumers:

| Consumer | before | after `listener.concurrency=3` |
|---|---|---|
| ledger | ~100 events/s | ~180/s |
| notifications | ~63/s | ~180/s |

The topics have three partitions but each listener ran one thread, so a service read them one after the other. Concurrency 3 is now the default for every service (`ObservabilityDefaults`). The ledger remains the slowest consumer, one transaction per event; a batch consumer that posts a page of payments in one transaction is the next step, and the thing to watch is consumer lag (the Grafana dashboard has it).

### The outbox relay
The relay sent each event and waited for its acknowledgement before the next, then marked each row published with its own UPDATE. It now hands a batch (up to 500 rows) to the producer, waits for all acknowledgements once, and marks the rows in one statement. The unpublished count stayed at zero under 100 payments/s afterwards. Delivery is still at-least-once; a failed batch rolls back and is sent again.

### Webhook fan-out
Delivery workers claim 20 deliveries per poll, send them on virtual threads and repeat while there is work. 900 deliveries were out 5 s after the last payment succeeded. An early run looked ten times worse; that was the webhooks consumer waiting behind the previous load's backlog, which is how the consumer finding above was noticed.

## Not done, and why
- **Partitioning `postings` by month.** The ledger's `postings` table is append-only and keyed by account, so partitioning by month is straightforward (range partitions on `created_at`, composite primary key) and would make old months cheap to archive or drop. At the volumes here (hundreds of thousands of rows) it buys nothing measurable and costs a composite key that every reference to a posting would need to carry, so it is a plan for when the table is big, not a migration to run now.
- **`api_request_logs` retention.** There is no such table: request history is in the logs (Loki, with its own retention). If it is added, it should be partitioned by day and the oldest partition dropped.
- **Batch consumers, projections for the list, index tuning with `EXPLAIN ANALYZE`.** The list query already uses `payment_intents_merchant_idx` and runs in under a millisecond; the cost was in the application, not in missing indexes.
- **Multiple instances.** Horizontal scale is how the 200/s target would be met; it needs a real cluster (see the deployment slice).
