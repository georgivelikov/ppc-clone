# 0003 — Hand-rolled JWT authentication in `identity`

- **Status:** Accepted (revisit in phase 8)
- **Date:** 2026-10-17

## Context
The dashboard needs login. Options: Keycloak (realistic, but a black box), or our own `identity` service issuing JWTs.

## Decision
`identity` issues tokens itself:

- **Access token:** JWT signed with RS256, 15-minute lifetime. Claims: `sub` (user id), `email`, `mid` (active merchant id), `role`.
- **Public keys:** published as a JWKS document at `/.well-known/jwks.json`. The `gateway` validates tokens using it.
- **Signing keys:** generated on first start and stored in the `signing_keys` table, so tokens survive restarts. Each key has a `kid`, which makes key rotation possible later.
- **Refresh token:** opaque random 256-bit value, stored only as a SHA-256 hash, sent as an `HttpOnly; SameSite=Strict` cookie scoped to `/auth`. It is rotated on every use. Presenting an already-rotated token is treated as theft and revokes every session of that user (reuse detection).
- **Passwords:** hashed with BCrypt via Spring Security's `DelegatingPasswordEncoder`, so the algorithm can be upgraded later.

## Consequences
- We learn JWT signing, JWKS, refresh rotation and reuse detection first-hand.
- Missing for now: email verification, password reset, 2FA. These are planned for phase 8.
- The private key sits unencrypted in Postgres. That is acceptable for a study project; phase 9 moves it to Vault.
