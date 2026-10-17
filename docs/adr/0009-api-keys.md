# 0009 — API keys and test/live mode

- **Status:** Accepted
- **Date:** 2026-10-17

## Context
Phase 3 lets a third-party server call the API with a key instead of a dashboard login. Keys must be revocable and rotatable, scoped, and bound to test or live mode, and downstream services should not have to learn a second kind of credential.

## Decision
- **Key format:** `sk_test_…`, `sk_live_…`, `pk_…` (publishable), `rk_…` (restricted) followed by 32 base62 characters from `SecureRandom` (about 190 bits).
- **Storage:** only the SHA-256 hash is stored, plus a 12-character display prefix and the last 4 characters. The secret is returned once, by the create and roll responses. A fast unsalted hash is enough because the keys are random, so there is nothing to brute-force. Publishable keys are hashed too and shown once, for simplicity.
- **Where checking happens:** the gateway. A `WebFilter` ahead of Spring Security recognises a key in `Authorization: Bearer`, asks identity (`POST /internal/api_keys/authenticate`, never routed by the gateway), and replaces the header with a **5-minute JWT** minted by identity for that key (`role=api`, `mid`, `livemode`, `key_type`, `scope`). Everything downstream sees an ordinary verified JWT.
- **Cache:** Caffeine in the gateway, 30 seconds, keyed by the key's hash, unknown keys cached as well. A revoked key therefore stops working within 30 seconds. Redis was considered; it is already used for rate limiting, but a per-instance cache keeps the hot path off the network and the staleness window is the same. Errors are never cached, and a failing identity makes the gateway answer 503 (fail closed).
- **Scopes:** secret keys can call everything except team-only routes (`/v1/api_keys`, `/v1/merchant`, `/me`, `/auth/**`). Restricted keys carry `resource:read` or `resource:write` (write implies read), mapped from the path in `KeyPolicy`; unknown paths are denied. Publishable keys are refused everywhere except the public `/checkout/**` and `/vault/**` routes, where they are ignored (those routes are public already).
- **Rolling:** creates a new key with the same settings. The old key expires after a grace period (0 to 7 days) or immediately when none is given.
- **Mode separation:** an API key's mode is the JWT `livemode` claim and **always wins** over a `?livemode=` query parameter. Dashboard sessions carry no claim: they use the parameter (default test) in ledger and payouts, and test data in payments until the dashboard gets a mode switch. Payments filter every query by `livemode`; idempotency keys are scoped per merchant and mode; by-id lookups in payouts and bank accounts add the mode when the token has one.

## Consequences
- Downstream services need no API-key code, only `RequestMode.resolve(claim, param)`.
- Rolling or revoking a key takes up to 30 seconds to be felt on every gateway instance.
- A rolled key's grace period cannot be extended; roll again instead.
- Not yet covered (later slices): request logs, rate limits per key, and the dashboard mode toggle.
