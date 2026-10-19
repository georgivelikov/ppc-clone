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
| 3 | [Developer platform](docs/plans/phase-03-developer-platform.md) — API keys, webhooks, SDK, demo shop, docs | ✅ Built |
| 4 | [Links, QR & Virtual POS](docs/plans/phase-04-links-qr-pos.md) — payment links, QR codes, charge-with-QR, virtual terminal | ✅ Built |
| 5 | [Billing](docs/plans/phase-05-billing.md) — saved cards, pre-auth, subscriptions, invoices, split payments | ✅ Built |
| 6 | [BNPL aggregator](docs/plans/phase-06-bnpl.md) — fake providers, routing engine, circuit breakers | ✅ Built |
| 7 | [Methods & localisation](docs/plans/phase-07-methods-localisation.md) — wallets, BLIK, FX, i18n | ✅ Built |
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
./gradlew :services:links:bootRun         # http://localhost:8087  (payment links, QR codes)
./gradlew :services:billing:bootRun       # http://localhost:8088  (products, subscriptions, invoices)
./gradlew :services:bnpl:bootRun          # http://localhost:8091  (BNPL aggregator: offers from several providers, routing)
./gradlew :services:bnpl-sim:bootRun      # http://localhost:8089  (four fake buy-now-pay-later providers, demo controls under /admin)
./gradlew :services:gateway:bootRun       # http://localhost:8080

# 3. Dashboard
cd frontend
npx pnpm@10.34.7 install
npx pnpm@10.34.7 dev                      # dashboard, http://localhost:5173
npx pnpm@10.34.7 --filter @cutpay/checkout dev   # hosted checkout and card element, http://localhost:5174
npx pnpm@10.34.7 --filter @cutpay/site dev       # marketing site and docs, http://localhost:5176
npx pnpm@10.34.7 --filter @cutpay/demo-shop dev  # example integration, http://localhost:5175 (backend on 4000)
```

On Windows use `gradlew.bat` instead of `./gradlew`.

| Tool | URL |
|---|---|
| Dashboard | http://localhost:5173 |
| Hosted checkout | http://localhost:5174 (payment links open at `/l/{slug}`) |
| Site and docs | http://localhost:5176 (API reference at `/docs/api`, rendered from [docs/api/openapi.yaml](docs/api/openapi.yaml)) |
| Demo shop | http://localhost:5175 (see below) |
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
| 4000 0000 0000 3220 | 3DS challenge (once when saved: then it can be charged off-session) |
| 4000 0027 6000 3184 | 3DS challenge on **every** charge: an off-session charge fails with `authentication_required` |
| 4000 0000 0000 0341 | Can be saved, but every charge is declined (a subscription card that went bad) |
| 4000 0000 0000 0002 | Declined |
| 4000 0000 0000 9995 | Insufficient funds |
| 4000 0000 0000 0069 | Expired card |
| 4000 0000 0000 0119 | Processing error (acquirer returns a 5xx) |
| 4000 0000 0000 7775 | Acquirer times out after 10 s |
| 5555 5555 5555 4444 | Success (Mastercard) |
| 4000 0100 0000 0001 | Success, EEA **business** card (higher fee) |
| 4000 0200 0000 0000 | Success, **non-EEA** consumer card (higher fee) |
| 4000 0300 0000 0009 | Success, non-EEA business card (higher fee) |

Use any future expiry and any CVC. The 3DS code on the fake bank page is `1234`.

Full list in [phase 1](docs/plans/phase-01-card-payments.md).

## Build an integration

The platform is meant to be used like a real one:

1. Sign up in the dashboard and open **Developers → API keys** to create a secret key (server) and a publishable key (web page). Each is shown once.
2. Call the API with `Authorization: Bearer sk_test_…` (see the [API reference](docs/api/openapi.yaml)), or add the card field to a page with the SDK served at `http://localhost:5174/v1/cutpay.js`.
3. Add a webhook endpoint under **Developers → Webhooks**; requests are signed with `Cutpay-Signature` and retried for three days. **Developers → Logs** shows every call.

The [demo shop](frontend/apps/demo-shop) is a complete example: a Node backend that holds the secret key, prices orders itself and trusts only verified webhooks, with a storefront that pays either through the embedded card field or the hosted checkout. Paste your keys into its Settings page and register `http://localhost:4000/webhooks/cutpay` as a webhook endpoint.

## Links, QR codes and the terminal

No website needed. In the dashboard:

- **Links** creates a payment link (fixed price, customer chooses, or products) with an optional limit on payments, an expiry and fields to collect. Each link has a QR code you can recolour and download (SVG, PNG) and a print-ready A6 table card (PDF). A link with `max_uses` never takes more payments than it allows, even when customers race for the last one ([ADR 0014](docs/adr/0014-links-qr-and-virtual-pos.md)).
- **Terminal → Charge with QR** shows a full-screen code for an amount; the page turns into a green tick within about a second of the customer paying, through a Server-Sent Events stream (`GET /v1/payment_intents/{id}/stream`).
- **Terminal → Virtual POS** keys in a card for a phone or mail order. The payment has `channel: "moto"` and the simulated bank skips 3-D Secure, as card-not-present orders are outside strong customer authentication. Try the 3-D Secure test card `4000 0000 0000 3220` here and at a normal checkout to see the difference.

To scan a code with a phone on the same network, serve the checkout on your computer's address and tell the services where it is: run `npx pnpm@10.34.7 --filter @cutpay/checkout dev --host`, and start the links and payments services with `CUTPAY_LINKS_CHECKOUT_URL` and `CUTPAY_CHECKOUT_URL` set to `http://<your-ip>:5174` (the codes encode that address).

## Billing, subscriptions and split payments

- **Customers** keep saved cards. "Add a card" gives you a link to a hosted page where the customer enters it without being charged; "Charge saved card" then takes a payment without them ([ADR 0015](docs/adr/0015-billing-saved-cards-and-preauthorisation.md)).
- **Terminal → Pre-authorise only** holds an amount on a card; the payment then shows **Capture** (all or part, the rest is released) and **Release hold**. A hold nobody captures is released after seven days.
- **Products** hold prices; **Subscriptions** bills them every period, with trials, plan changes that credit the unused part of the old plan and charge the rest at the new one (the preview shows the lines first), pause and cancel. When a payment fails the subscription goes past due and the invoice is tried again on days 1, 3, 5 and 7, then the subscription is canceled (or kept unpaid, as you choose). A downgrade that credits more than the next invoice leaves the rest as customer credit, which later invoices use before the card is charged; the Subscriptions page also charts churn month by month. The customer is emailed at every step; the emails are recorded under the subscription until real delivery arrives in phase 8.
- **Invoices** also covers one-off invoices: draft, finalize (numbered INV-0001…), email a link to the page at `/i/{token}` where the customer pays or downloads the PDF.
- **Connected accounts** are the sellers on your platform. Create a payment with `transfer_data` (or pick an account in "Create test payment") and each account is credited its part, with its own balance and its own payouts; refunds are taken back in proportion ([ADR 0016](docs/adr/0016-split-payments.md)).

## Pay in instalments

- At the checkout a **Pay in instalments** tab appears when a provider can finance the price: SplitNow, Lendo, KreditKo or PayLaterEU, four pretend providers with their own limits, fees and ways of answering ([ADR 0017](docs/adr/0017-bnpl-provider-simulator.md)). The page at `http://localhost:8089` switches one off, slows it down or makes it hang.
- **Instalments** in the dashboard sets which providers you offer and how the best one is chosen (the customer, cheapest, fastest, most likely to approve, or a weighted mix), and shows how each has done and whether its circuit is open. A payment says who financed it and why that provider was chosen ([ADR 0018](docs/adr/0018-bnpl-aggregation-and-routing.md)).
- You are paid the price less the provider fee as soon as it approves ([ADR 0019](docs/adr/0019-bnpl-applications-and-settlement-of-the-payment.md)); the provider settles a day later and refunds go through it ([ADR 0020](docs/adr/0020-bnpl-settlement-and-refunds.md)). A provider that is down drops out of the offers and comes back by itself ([ADR 0021](docs/adr/0021-bnpl-resilience-cascade-and-learning.md)).

## Wallets, BLIK, currencies and languages

- The checkout offers **Wallet** (a pretend Apple Pay and Google Pay: pick a stored test card, confirm "on the device"; no 3-D Secure) and, for payments in PLN, **BLIK** (type `777123`, then approve in the pretend bank app at `/vault/sim/blik-app`; `000000` is declined). Which methods a payment offers is one rule list, and Settings, Payment methods switches wallets and BLIK off ([ADR 0022](docs/adr/0022-wallets-and-blik.md)).
- Money stays in the currency it was paid in. **Balances** shows every currency, converts available money with a quote that holds a rate for 15 minutes, and can convert other currencies by itself when they become available. A conversion costs 1% over the (simulated) market rate and is posted so that the books balance in every currency ([ADR 0023](docs/adr/0023-fx-conversions-in-the-ledger.md)).
- The checkout speaks English, Bulgarian, Romanian, Polish, Hungarian and Greek (`?locale=pl`, or the `locale` of the payment, or the browser's language), and writes prices the way each does; in Bulgarian a euro price also shows leva. The dashboard is in English and Bulgarian. `pnpm i18n:check` finds missing translations ([ADR 0024](docs/adr/0024-localisation.md)).

Billing, ledger and payouts run on simulated clocks. To watch a trial end and the next month renew:

```bash
curl -X POST 'http://localhost:8088/admin/clock/advance?days=8'    # billing: the trial ends, the first invoice is charged
curl -X POST 'http://localhost:8088/admin/clock/advance?days=32'   # billing: the next period is invoiced
curl -X POST 'http://localhost:8082/admin/clock/advance?days=8'    # payments: an uncaptured hold is released after 7 days
```

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
