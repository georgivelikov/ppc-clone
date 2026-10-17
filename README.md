# Cutpay

An **educational clone** of [Paypercut](https://paypercut.com/), the Sofia-based payments platform for SMBs across Central & Eastern Europe — built to learn how a modern payment service provider works end to end.

> ⚠️ **Study project only.** No real money moves. Every bank, card network, BNPL provider, wallet and KYC provider is a local simulator. Not affiliated with Paypercut; "Cutpay" uses its own name and design.

## What it covers

- **Online checkout** — hosted page and embeddable SDK, cards with 3DS, Apple Pay / Google Pay (simulated), BLIK
- **Payment links & QR codes** — get paid without a website
- **Virtual POS terminal** — keyed-in card payments from the dashboard
- **Billing & payouts** — subscriptions, invoices, pre-authorisation, split payments, scheduled settlement
- **BNPL aggregator** — several fake BNPL providers behind one integration, with smart routing
- **Multi-currency & localisation** — EUR, RON, PLN, HUF, CZK, GBP, USD, CHF; checkout in en/bg/ro/pl/hu/el
- **Merchant dashboard** — payments, balances, payouts, customers, developers (API keys, webhooks, logs), team, reports
- **Digital onboarding** — simulated KYC/KYB, sandbox before live

## Tech stack

| Layer | Technologies |
|---|---|
| Backend | Java 25, Spring Boot 4, Gradle multi-module, Spring Data JPA, Flyway |
| Messaging | Apache Kafka (KRaft), Schema Registry, Spring Kafka |
| Database | PostgreSQL 16 (database per service), Redis |
| Frontend | React 19, TypeScript, Vite, pnpm workspaces, TanStack Query, Tailwind + shadcn/ui |
| Infra | Docker Compose, Mailpit, Kafka UI, Prometheus, Grafana, Tempo, OpenTelemetry |
| Testing | JUnit 5, Testcontainers, Playwright, k6 |

## Architecture

Event-driven microservices: `gateway`, `identity`, `payments`, `acquirer-sim`, `ledger`, `payouts`, `links`, `billing`, `bnpl`, `bnpl-sim`, `webhooks`, `notifications`, and more — each owning its own database and communicating through Kafka using the transactional outbox / idempotent inbox patterns. Money is always stored as integer minor units with an ISO currency code.

See [docs/plans/README.md](docs/plans/README.md) for the full architecture, conventions and open design decisions.

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 0 | [Foundation](docs/plans/phase-00-foundation.md) — infra, monorepo, gateway, auth, dashboard shell | ✅ Built |
| 1 | [Card payments core](docs/plans/phase-01-card-payments.md) — payment intents, fake acquirer, hosted checkout, 3DS, refunds | ✅ Built |
| 2 | [Ledger & payouts](docs/plans/phase-02-ledger-payouts.md) — double-entry ledger, fees, balances, settlement | ✅ Built |
| 3 | [Developer platform](docs/plans/phase-03-developer-platform.md) — API keys, webhooks, SDK, demo shop, docs | 🚧 In progress (API keys, mode separation and webhooks done) |
| 4 | [Links, QR & Virtual POS](docs/plans/phase-04-links-qr-pos.md) | ⏳ Planned |
| 5 | [Billing](docs/plans/phase-05-billing.md) — subscriptions, invoices, pre-auth, split payments | ⏳ Planned |
| 6 | [BNPL aggregator](docs/plans/phase-06-bnpl.md) — fake providers, routing engine | ⏳ Planned |
| 7 | [Methods & localisation](docs/plans/phase-07-methods-localisation.md) — wallets, BLIK, FX, i18n | ⏳ Planned |
| 8 | [Merchant operations](docs/plans/phase-08-merchant-ops.md) — KYC, team, disputes, reports, back-office | ⏳ Planned |
| 9 | [Hardening](docs/plans/phase-09-hardening.md) — observability, chaos, performance, security, plugins | ⏳ Planned |

## Repository layout (target)

```
backend/    Gradle multi-module: libs/ and services/
frontend/   pnpm workspace: apps/ (dashboard, checkout, demo-shop, site) and packages/
infra/      docker-compose and observability config
docs/       plans, ADRs, API specs
e2e/        Playwright end-to-end tests
```

## Getting started

**Prerequisites:** Docker, JDK 25, Node.js 22+ (pnpm is fetched on demand through `npx`).

```bash
# 1. Infrastructure: Postgres, Kafka, Redis, Mailpit, Kafka UI
docker compose -f infra/docker-compose.yml up -d

# 2. Backend (one terminal per service)
cd backend
./gradlew :services:identity:bootRun      # http://localhost:8081
./gradlew :services:payments:bootRun      # http://localhost:8082
./gradlew :services:acquirer-sim:bootRun  # http://localhost:8083  (fake card network)
./gradlew :services:ledger:bootRun        # http://localhost:8084  (double-entry ledger, fees, balances)
./gradlew :services:payouts:bootRun       # http://localhost:8085  (bank accounts, payouts, fake bank)
./gradlew :services:webhooks:bootRun      # http://localhost:8086  (webhook endpoints, events, deliveries)
./gradlew :services:gateway:bootRun       # http://localhost:8080

# 3. Dashboard
cd frontend
npx pnpm@10.34.7 install
npx pnpm@10.34.7 dev                      # dashboard, http://localhost:5173
npx pnpm@10.34.7 --filter @cutpay/checkout dev   # hosted checkout, http://localhost:5174
```

On Windows use `gradlew.bat` instead of `./gradlew`.

| Tool | URL |
|---|---|
| Dashboard | http://localhost:5173 |
| Hosted checkout | http://localhost:5174 (links are created from the dashboard's Payments page) |
| API gateway | http://localhost:8080 |
| Kafka UI | http://localhost:8090 |
| Mailpit (fake inbox) | http://localhost:8025 |

### Tests

```bash
cd backend  && ./gradlew build             # unit + Testcontainers integration tests (needs Docker)
cd frontend && npx pnpm@10.34.7 -r test    # Vitest
cd e2e && npm install && npx playwright install chromium && npx playwright test   # needs step 1 running
```

## Test cards

| Card number | Result |
|---|---|
| 4242 4242 4242 4242 | Success |
| 4000 0000 0000 3220 | 3DS challenge |
| 4000 0000 0000 0002 | Declined |
| 4000 0000 0000 9995 | Insufficient funds |
| 4000 0000 0000 0069 | Expired card |
| 4000 0000 0000 0119 | Processing error (acquirer returns a 5xx) |
| 4000 0000 0000 7777 | Acquirer times out after 10 s |
| 5555 5555 5555 4444 | Success (Mastercard) |
| 4000 0100 0000 0001 | Success, EEA **business** card (higher fee) |
| 4000 0200 0000 0000 | Success, **non-EEA** consumer card (higher fee) |
| 4000 0300 0000 0009 | Success, non-EEA business card (higher fee) |

Use any future expiry and any CVC. The 3DS code on the fake bank page is `1234`.

Full list in [phase 1](docs/plans/phase-01-card-payments.md).

## Payouts and time travel

Fees follow the [phase 2 pricing](docs/plans/phase-02-ledger-payouts.md#pricing-mimics-paypercut): 1.29% + €0.10 for EEA consumer cards, 2.69% + €0.10 otherwise. Funds are pending for two days, then available, then paid out to your bank account.

- A bank account IBAN ending in `0000` (for example `DE94370400440532010000`) makes the fake bank reject the payout (`account_closed`); `BG80BNBG96611020345678` works.
- The ledger and payouts services run on a simulated clock. Move both forward to see a payment become available and get paid out without waiting:

```bash
curl -X POST 'http://localhost:8084/admin/clock/advance?days=2'
curl -X POST 'http://localhost:8085/admin/clock/advance?days=2'
curl http://localhost:8084/admin/trial-balance      # every posting summed: always 0
```

## Disclaimer

This repository exists purely for learning. It does not process real payments, must not be used with real card data, and is not affiliated with or endorsed by Paypercut.
