# Deploying Cutpay to Kubernetes

Everything runs in `docker compose` for development (`infra/`). This directory is the same system the way a cluster wants it: container images, a Helm chart, operators for the stateful parts, autoscaling on what actually limits the system, and a GitOps definition. It is written to run on a laptop with [kind](https://kind.sigs.k8s.io/) and to carry over to a real cluster by changing values.

```
deploy/
  build-images.sh          one Dockerfile for the 14 services (backend/Dockerfile), one for the 4 web apps (frontend/Dockerfile)
  kind/kind-config.yaml    a local cluster whose port 80 is the laptop's port 80
  helm/cutpay/             the chart: services, web apps, ingress, Strimzi Kafka, CloudNativePG Postgres, KEDA, canary
  argocd/application.yaml  GitOps: the cluster follows this repository
```

## What was checked, and what was not
- Both Dockerfiles build and the images start (`kyc-sim` was run and answered its health check as a non-root user; the checkout image was run and its headers inspected, which found and fixed a bug where the card field would have been un-frameable).
- The chart passes `helm lint`, and the rendered manifests, operator resources included (Strimzi, CloudNativePG, KEDA, Argo Rollouts), validate against their schemas with kubeconform (CI repeats this).
- **It has not been run in a cluster.** kind and the operators were not available where this was written, so expect to fix small things on the first install (resource sizes, a missing environment variable for a service). The environment variable names were derived from each service's `application.yml` keys, which is where a mistake is most likely.

## Try it on kind
```
kind create cluster --name cutpay --config deploy/kind/kind-config.yaml

# Ingress controller
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

# Operators: Kafka (Strimzi) and Postgres (CloudNativePG). Optional: KEDA, Argo Rollouts.
helm install strimzi oci://quay.io/strimzi-helm/strimzi-kafka-operator -n operators --create-namespace
kubectl apply --server-side -f https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.27/releases/cnpg-1.27.0.yaml
helm repo add kedacore https://kedacore.github.io/charts && helm install keda kedacore/keda -n keda --create-namespace

# The images, then the chart
deploy/build-images.sh dev --load-kind
helm install cutpay deploy/helm/cutpay -n cutpay --create-namespace --set keda.enabled=true
kubectl -n cutpay get pods -w
```
Then open http://checkout.localtest.me, http://dashboard.localtest.me, http://backoffice.localtest.me and http://www.localtest.me (`localtest.me` and its subdomains resolve to 127.0.0.1). The API is at http://api.localtest.me.

## How the pieces fit
- **Images.** The layers of the Spring Boot jar are separated (dependencies, loader, application), so a rebuild after a code change uploads kilobytes, not 100 MB. The process runs as a non-root user; the pod runs with a read-only root file system (only `/tmp` is writable), no capabilities and the default seccomp profile.
- **Probes.** A startup probe gives the JVM up to three minutes; readiness is Spring's readiness group (database and Kafka reachable); liveness is the liveness group. Readiness gates traffic, liveness restarts, and they differ on purpose: a service that has lost its database should leave the load balancer, not be killed.
- **Postgres: CloudNativePG.** One cluster, a database per service (created in `postInitSQL`), credentials from the secret the operator creates. A pod is not a database: the operator handles failover, replicas, backups and minor upgrades. Raise `platform.postgres.instances` to 3 for a primary with two replicas.
- **Kafka: Strimzi.** A KRaft cluster; topics are created on first use with three partitions, like locally. Raise `platform.kafka.replicas` to 3 and `min.insync.replicas` follows.
- **Scaling.** The gateway and payments scale on CPU (HPA). The consumers (ledger, webhooks, notifications, reporting) scale with KEDA on consumer-group lag, because a backlog is what says more consumers would help; the ceiling is three because the topics have three partitions, and replicas beyond that would sit idle. `docs/perf/` found exactly this bottleneck: consumers fell behind producers.
- **Web apps.** nginx serves the static build, proxies the API paths to the gateway, and sends the security headers (including the strict CSP on the checkout) that the dev server plugin sends locally. `/elements/` is the one place the checkout may be framed.
- **Dev dependencies.** Redis, Mailpit and a dev-mode Vault are small Deployments (`devDependencies.enabled`). In a real cluster use managed Redis, a real mail provider and a real Vault.

## Releasing payments
Payments is the service where a bad release costs money, so it gets a canary:
```
helm upgrade cutpay deploy/helm/cutpay -n cutpay --set rollout.enabled=true   # needs Argo Rollouts
kubectl argo rollouts get rollout payments -n cutpay -w
kubectl argo rollouts abort payments -n cutpay          # back to the old version at once
```
The Rollout adopts the pod template of the payments Deployment (`workloadRef`), runs the new version at 10% of the pods, waits, goes to 50%, waits, then promotes. The pause is when you watch the error rate and latency on the Grafana dashboard and the SLO alerts. Turn off the payments HPA while a Rollout owns the pods.

### Database changes and zero downtime: expand, then contract
During a canary, old and new code run at the same time against one database, and a rollback puts the old code back. So a migration must work for both versions. Never rename or drop in the same release that stops using a column:
1. **Expand.** Add the new column or table (nullable, or with a default). Both versions run. Deploy.
2. **Migrate.** Release the code that writes both and reads the new one; backfill old rows (the IBAN encryption backfill in payouts is an example: rows are encrypted in place at start-up, and plain rows are still read until then).
3. **Contract.** Only after no running version needs the old shape, a later release drops it.

Flyway runs migrations when a service starts, so with several replicas the first one to start applies them under Flyway's lock; keep each migration short and avoid long table locks (`CREATE INDEX CONCURRENTLY` needs its own non-transactional migration).

## GitOps
`argocd/application.yaml` makes Argo CD apply `deploy/helm/cutpay` from `main` and revert manual changes (`selfHeal`). Install Argo CD, replace the repository URL, apply the file. The image tag then becomes the thing a pull request changes.

## What a real environment still needs
Secrets from a secret manager rather than values, TLS (cert-manager) on the ingress, network policies between the pods (the threat model's top gap), resource sizes from load tests rather than guesses, Prometheus scraping (the pods carry the annotations; the rules and dashboards from the compose stack carry over), and an OTLP collector endpoint for traces.
