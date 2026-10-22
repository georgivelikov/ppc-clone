# 0033 — Performance: measure, then change what the measurement points at

- **Status:** Accepted
- **Date:** 2026-10-22

## Context
The plan asks for 200 checkout confirms a second and 1,000 API reads a second, and a list of tuning ideas (pool sizes, partitions, batch consumers, indexes, virtual threads, partitioning). Tuning from a list without measuring tends to change the wrong thing, so the work went the other way round: k6 scenarios first (`perf/`), then a change only where the numbers pointed.

## Decision
- Load scenarios are code: k6 for checkout and reads, a Node script for the webhook burst (it needs a receiver). They run against the same jar stack as the chaos experiments, and `docs/perf/README.md` records how to run them and what they showed.
- Changes made, each because a measurement found it:
  - The payment list loaded attempts one payment at a time (N+1). It loads them for the page in one query. Reads went from about 110 to about 300 a second.
  - Every listener now runs three consumer threads (the topics have three partitions) via a shared default. The ledger and notifications consumers drained two to three times faster.
  - The outbox relay sends a batch, waits once for the acknowledgements and marks the rows in one statement, instead of one round trip and one UPDATE per event.
- Changes tried and not kept: a bigger connection pool and virtual threads in payments. Neither was a clear win on this machine, because it was CPU-bound.
- Not done: `postings` partitioning and `api_request_logs` retention (no such table), with reasons in `docs/perf/README.md`.

## Consequences
- The 200/s and 1,000/s targets are not met on a laptop that also runs the load generator, Docker and the browser; the documented ceiling is about 80 confirms/s and 300 reads/s. The remaining route is more instances and a separate database, which the deployment slice sets up.
- A load run leaves consumer backlog on the shared Kafka, and the integration tests (which use the real consumer groups) then wait behind it. `docs/perf/README.md` has the offset reset.
- Three consumer threads per listener mean three concurrent transactions per consumer, which is safe because consumers are idempotent and records with the same key stay on one partition, but the ledger's per-merchant account rows now see real concurrency under load.
