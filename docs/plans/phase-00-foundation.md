# Phase 0 — Foundation

## Goal
A runnable skeleton: infrastructure in Docker, a Gradle monorepo, a pnpm monorepo, `gateway` + `identity` services, and a dashboard where a merchant can **sign up, log in, and see an empty home page**.

## Services / apps touched
- `infra/docker-compose.yml` (new)
- `backend/services/gateway`, `backend/services/identity` (new)
- `backend/libs/common-web`, `common-events`, `common-money`, `common-test` (new)
- `frontend/apps/dashboard`, `frontend/packages/ui`, `frontend/packages/api-client` (new)

## Infrastructure (docker-compose)
| Container | Image | Port |
|---|---|---|
| postgres | postgres:16 | 5432 (init script creates one DB per service) |
| kafka | apache/kafka (KRaft) | 9092 |
| schema-registry | confluentinc/cp-schema-registry | 8081 |
| kafka-ui | provectuslabs/kafka-ui | 8090 |
| redis | redis:7 | 6379 |
| mailpit | axllent/mailpit | 8025 (UI), 1025 (SMTP) |

Observability stack (Prometheus/Grafana/Tempo) is added in phase 9, but expose `/actuator/prometheus` from day one.

## Data model (`identity` DB)
```
merchants(id PK 'mer_', legal_name, display_name, country CHAR(2), default_currency CHAR(3),
          status ENUM(pending_kyc, active, suspended), created_at)
users(id PK 'usr_', email UNIQUE, password_hash, full_name, created_at, last_login_at)
memberships(user_id FK, merchant_id FK, role ENUM(owner, admin, developer, viewer), PK(user_id, merchant_id))
refresh_tokens(id PK, user_id FK, token_hash, expires_at, revoked_at)
outbox(id PK, aggregate_id, event_type, payload JSONB/BYTEA, created_at, published_at NULL)
```

## REST endpoints
| Method | Path | Notes |
|---|---|---|
| POST | `/auth/signup` | creates user + merchant + owner membership |
| POST | `/auth/login` | returns access JWT (15 min) + refresh cookie (httpOnly) |
| POST | `/auth/refresh` | rotate refresh token |
| POST | `/auth/logout` | revoke refresh token |
| GET | `/me` | user + memberships |
| GET/PATCH | `/v1/merchant` | current merchant profile |

Gateway validates JWT (RS256, JWKS served by identity at `/.well-known/jwks.json`) and forwards `X-Merchant-Id`, `X-User-Id`, `X-Livemode` headers.

## Kafka
- Topic `merchant.events`: `MerchantCreated`, `MerchantUpdated`.
- Implement the **outbox relay** in `common-events` (scheduled poller → `KafkaTemplate` → mark published). Reused by every later service.
- No consumers yet; verify via Kafka UI.

## UI screens (dashboard)
- Sign up, Log in, Forgot password (stub)
- App shell: sidebar (Home, Payments, Balances, Payouts, Links, Customers, Subscriptions, Developers, Settings — most disabled "coming soon"), top bar with **Test mode** toggle and merchant switcher
- Home: empty-state cards
- Settings → Business profile

## Tasks
1. `git` hygiene: `.gitignore`, `.editorconfig`, root `README.md`, `docs/adr/0001-record-architecture-decisions.md`.
2. Write ADRs for decisions D1–D5 (see [README](README.md)).
3. `infra/docker-compose.yml` + `infra/postgres/init.sql`.
4. Gradle: `settings.gradle.kts`, `build-logic` convention plugins (java-conventions, spring-service), version catalog `libs.versions.toml`.
5. `common-money`: `Money`, `Currency` validation, arithmetic, unit tests (property tests with jqwik).
6. `common-web`: error model, `ProblemDetail` → our error JSON, request-id filter, prefixed-ULID generator.
7. `common-events`: event envelope, `OutboxRepository`, `OutboxRelay`, `InboxGuard`.
8. `identity` service: entities, Flyway migrations, BCrypt/Argon2 hashing, JWT issuing with Nimbus, JWKS endpoint.
9. `gateway`: Spring Cloud Gateway routes, JWT filter, CORS, rate-limit with Redis (RequestRateLimiter).
10. Frontend: pnpm workspace, `packages/ui` (Tailwind + shadcn primitives, design tokens, dark mode), `packages/api-client` (typed fetch wrapper; later generated from OpenAPI with `openapi-typescript`).
11. Dashboard app: routing, auth context with silent refresh, protected routes, the shell.
12. CI: GitHub Actions — `./gradlew build` (with Testcontainers), `pnpm -r lint test build`.
13. `Makefile`/`justfile` (or PowerShell script): `up`, `down`, `dev`, `test`.

## Learning objectives
- Gradle multi-module + convention plugins
- Spring Boot service anatomy, Flyway, Testcontainers
- JWT (asymmetric signing, JWKS, refresh rotation)
- API gateway pattern
- Transactional outbox basics

## Acceptance criteria
- `docker compose up -d` + `./gradlew bootRun` (or compose profile) brings everything up; all `/actuator/health` = UP.
- Sign-up → login → dashboard shows merchant name; refresh survives page reload.
- `MerchantCreated` event visible in Kafka UI.
- Tests: identity integration test (Testcontainers Postgres + Kafka) asserting outbox → topic; Playwright `signup.spec.ts`.

## Open questions
- Email verification on sign-up now or in phase 8? (Default: phase 8.)
