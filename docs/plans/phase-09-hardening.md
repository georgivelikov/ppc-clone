# Phase 9 — Hardening: observability, resilience, security, performance & plugins

## Goal
Turn the working system into a **production-grade-shaped** one: full observability, proven resilience under failure, security review, performance targets, CDC-based outbox, Kubernetes deployment, and an **e-commerce plugin** (WooCommerce) like Paypercut's.

## Observability
- **OpenTelemetry** Java agent on all services + browser SDK in checkout → traces in **Tempo**, metrics in **Prometheus**, logs in **Loki** (structured JSON logs with `trace_id`, `merchant_id`, `payment_intent_id`).
- Trace context propagated through Kafka headers → one trace from "Pay" click → payments → acquirer-sim → ledger → webhooks.
- **Grafana dashboards:** payments funnel & success rate, acquirer latency p50/p95/p99, consumer lag per group, outbox backlog, webhook delivery success, BNPL provider health, JVM/DB pool.
- **Business SLOs:** checkout confirm p95 < 800 ms; webhook delivered < 30 s p95; consumer lag < 1,000. Alertmanager rules → Mailpit.
- Synthetic monitoring: scheduled Playwright "canary" payment every 5 min.

## Resilience & chaos
- Toxiproxy between services and Postgres/Kafka/acquirer-sim.
- Experiments (each documented with hypothesis → result):
  1. Kill `ledger` for 10 min during load → lag grows, then catches up; balances correct.
  2. Acquirer-sim 50% errors → circuit opens, checkout shows friendly error, no double charges.
  3. Duplicate & reordered Kafka messages (test producer) → no double postings.
  4. Postgres failover (Patroni or just restart) → services recover.
  5. Poison message → lands in DLT; DLT reprocessing tool in back-office.
- **Debezium** CDC replaces polling outbox relay (D4) — compare latency/throughput.
- Reconciliation jobs: payments vs ledger vs acquirer-sim settlement files (generate daily CSV in acquirer-sim) → mismatches reported. This is how real PSPs catch bugs.

## Performance
- **k6** scenarios: 200 checkout confirms/s, 1,000 API reads/s, webhook fan-out burst.
- Tune: HikariCP pools, Kafka partitions (& consumer concurrency), batch consumers for ledger, Postgres indexes (EXPLAIN ANALYZE), virtual threads (Java 21) for I/O-bound services.
- Partitioning strategy for `postings` (by month) and `api_request_logs` (retention).
- Document results in `docs/perf/`.

## Security
- Threat model (STRIDE) document for checkout & API.
- OWASP ZAP baseline scan in CI; dependency scanning (OWASP Dependency-Check / Renovate); secret scanning.
- CSP + SRI for checkout & `cutpay.js`; iframe sandboxing review; CSRF on dashboard; rate limiting & bot protection on public endpoints.
- Encryption: field-level encryption for PII (customer email/phone, IBAN) with a local KMS (HashiCorp Vault transit engine) — envelope encryption lesson.
- mTLS between services (optional, via service mesh).
- PCI-DSS mapping doc: which parts would be in scope and why tokenisation reduces it.
- Vulnerability disclosure page on site (Paypercut has one).

## Deployment
- Dockerfiles (Jib or layered jars), images per service.
- **Kubernetes** (kind/k3d locally) with Helm charts; Strimzi for Kafka, CloudNativePG for Postgres.
- GitOps with Argo CD (optional). Horizontal autoscaling on consumer lag (KEDA).
- Blue/green or canary for `payments` (Argo Rollouts) — discuss zero-downtime DB migrations (expand/contract).

## Plugins
- **WooCommerce plugin** (PHP): settings page (API keys, test mode), payment gateway class using hosted checkout redirect, webhook handler updating order status, refunds from WooCommerce admin. Run WordPress + WooCommerce in docker compose for testing.
- Optional: Shopify-style app concept or a **headless JS plugin** for any site (`<script>` + data attributes → payment button).
- Optional: **mobile** — React Native "Cutpay Merchant" app (payments feed, push notifications, QR charge).

## Learning objectives
- Distributed tracing across async boundaries
- SLOs, alerting, capacity planning
- Chaos engineering & reconciliation
- CDC (Debezium) vs polling outbox
- Kubernetes operators for stateful systems
- Security engineering for payment systems

## Acceptance criteria
- A single trace spans checkout → payments → acquirer-sim → Kafka → ledger → webhooks.
- All 5 chaos experiments documented with passing outcomes.
- k6 targets met on a dev laptop (or documented bottlenecks).
- `helm install` brings the whole platform up on kind; Playwright suite passes against it.
- WooCommerce store completes a test order, refund syncs back.
- Daily reconciliation shows zero mismatches after a full test run.

## Beyond (ideas backlog)
- Fraud/risk engine (rules + velocity checks with Kafka Streams, ML score stub)
- Open Banking pay-by-bank (simulated PSD2 PIS)
- Card issuing / virtual cards
- Tap-to-Pay on phone simulation
- Multi-region active/passive
