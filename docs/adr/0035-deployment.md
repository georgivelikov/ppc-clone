# 0035 — Deployment: images, a Helm chart, operators for state

- **Status:** Accepted
- **Date:** 2026-10-22

## Context
Until now the system ran from `docker compose` plus `bootRun`. A production-shaped system needs images, a description of how they run together, and an answer for the parts that must not be just pods: Kafka and Postgres.

## Decision
- **Two Dockerfiles, not eighteen.** One for the services (build argument `SERVICE`; Gradle builds inside the image, the jar is split into layers, the process runs as an unprivileged user) and one for the web apps (`APP`; build, then nginx). Images are built from the same sources the tests ran on; nothing is copied in from a developer machine.
- **The web images carry the security headers.** The checkout's strict CSP existed only in the Vite dev and preview server; production had no equivalent. nginx now sends them, with `/elements/` the single frameable path. Building it found a bug that tests of the dev server could not: the nginx `try_files` fallback is an internal redirect that lands in the catch-all location and replaces the headers, which would have stopped merchants embedding the card field. The location now rewrites instead.
- **One chart, driven by values.** Each service is an entry in `values.yaml` (port, database, scaling, environment); a loop renders Deployment, Service, PodDisruptionBudget, HPA or KEDA object. Adding a service is adding an entry. Pods run with a read-only root file system, no capabilities and distinct startup, readiness and liveness probes.
- **Operators for state.** Strimzi runs Kafka (KRaft) and CloudNativePG runs Postgres, each described by one resource in the chart. A StatefulSet someone wrote by hand does not fail over, take backups or upgrade; the operator does, which is the reason to use one. Each service gets its own database in the cluster, as locally.
- **Scale on the real bottleneck.** The performance work (ADR 0033) found that consumers, not the HTTP layer, fall behind. The ledger, webhooks, notifications and reporting scale with KEDA on consumer-group lag, up to the three partitions. The gateway and payments scale on CPU.
- **Releases.** Argo CD applies the chart from `main` (GitOps, with self-heal), and payments can be released as an Argo Rollouts canary that adopts the Deployment's pod template. Migrations follow expand/contract so old and new versions can share a database during a canary or a rollback (written down in `deploy/README.md`).

## Not done
- **Not exercised beyond a smoke journey.** The chart was installed on kind and the browser canary (sign up, pay on the hosted checkout, see the payment) passed against it, with KEDA scalers ready. The full Playwright suite was not run against the cluster; failover, backups and upgrades of the operators were not tried. The install found four defects (Strimzi's API version and supported Kafka versions, nginx and KEDA needing fully-qualified service names, and a front-end call that fails on plain http); see `deploy/README.md`.
- Network policies, mutual TLS, cert-manager TLS, a secret manager and real resource sizing are listed in `deploy/README.md` as what a real environment adds.
- Debezium in place of the polling outbox relay is covered separately (ADR 0038).

## Consequences
- A single Dockerfile means a build per service downloads Gradle dependencies; a BuildKit cache mount keeps that tolerable locally. CI builds two images as a smoke test, not all eighteen.
- Setting the pod user by number (999) ties the chart to the Dockerfile.
