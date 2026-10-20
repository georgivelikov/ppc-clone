# 0031 — Observability: one trace across the async boundaries

- **Status:** Accepted
- **Date:** 2026-10-21

## Context
A payment touches the gateway, payments, the acquirer simulator, Kafka, the ledger, webhooks and more. When something is slow or wrong, nobody should have to grep fourteen log files and guess which lines belong together.

## Decision

### Micrometer Tracing with the OpenTelemetry bridge, not the Java agent
The plan named the OpenTelemetry Java agent. Spring Boot 4 ships first-class OTel support (`spring-boot-starter-opentelemetry`), which gives the same traces without a second moving part on every JVM command line, works with `bootRun`, the e2e harness and Testcontainers unchanged, and puts metrics and traces on one set of `Observation`s. The agent stays an option for services that are not Spring.

`libs/common-observability` is on every service's classpath through the `cutpay.spring-service` plugin. An `EnvironmentPostProcessor` adds its settings at the lowest precedence (any service or environment variable wins): 100% sampling, OTLP/HTTP to Tempo on `localhost:4318`, Kafka send and receive observed, percentile histograms on `http.server.requests`, and JSON log files. `cutpay.observability.enabled=false` turns it all off; the shared Gradle test configuration sets that, so tests never export or write files.

### The outbox hop
Kafka header propagation is automatic for `KafkaTemplate.send` and `@KafkaListener`, but our events are not sent from the request: they sit in the `outbox` table until `OutboxRelay` polls. By then the request's span is gone, so the trace would break at every service. `OutboxWriter` therefore stores the writer's W3C `traceparent` in a new `outbox.traceparent` column (a migration in each of the ten services that own an outbox), and the relay starts an `outbox publish` span as a child of it before sending. The producer span, the consumer span in the next service and everything it does then continue the same trace. Rows written with no active trace keep a null column and publish as before.

Result, measured on the e2e card-payment run: one trace from the gateway's `POST` through payments, acquirer-sim, the ledger, webhooks, notifications and reporting.

### Scheduled jobs are not traced by default
The relay polls every 0.5 s and job runners every few seconds; as traces they were thousands of one-span entries. `ObservationPredicate` drops `tasks.scheduled.execution` unless `cutpay.observability.trace-scheduled=true`.

### Logs and metrics
Services run on the host, so each writes JSON (`logstash` format) to `logs/<service>.log`; Grafana Alloy tails that folder into Loki. Trace and span ids come from MDC, so Loki lines carry `trace_id` as structured metadata and Grafana links a log line to its trace and back. Prometheus scrapes `/actuator/prometheus` on all fourteen ports. The Compose stack is behind the `observability` profile.

### SLO alerts
`infra/observability/prometheus/rules/slo.yml`: checkout confirm p95 over 800 ms, consumer lag over 1,000, 5xx ratio over 5%, a service down, connection-pool waits. Alertmanager mails them to Mailpit.

## Consequences
- Every outbox table gained a nullable column; the reference DDL in `common-events` shows it.
- Sampling at 100% is right for a laptop and wrong for production; it is one property.
- Not yet done from the phase plan: the browser SDK span (checkout to gateway), `merchant_id` and `payment_intent_id` in the log MDC, a webhook-delivery and funnel dashboard, and the Playwright canary. They follow in later slices.
