# 0034 — Security hardening: headers, scanning, field encryption

- **Status:** Accepted
- **Date:** 2026-10-22

## Context
The system already had the structural protections (card data kept in a vault, signed webhooks, hashed API keys, idempotency, rate limits at the gateway). What it lacked was a written threat model, checks that keep it that way, protection for the page that handles cards, and encryption of personal data at rest. This ADR records what was added and what was knowingly left. The detail is in `docs/security/`.

## Decision

### Protect the page that reads cards
The hosted checkout and card field send a Content-Security-Policy that allows scripts, styles, images and connections from their own origin only (`default-src 'self'`, `connect-src 'self'`, `object-src 'none'`, `base-uri 'none'`, `form-action 'self'`), plus `Referrer-Policy: no-referrer` (the payment secret is in the address), `X-Content-Type-Options: nosniff` and a restrictive Permissions-Policy. The dev server needs inline scripts and a WebSocket, so it sends a relaxed variant; the production build and its preview send the strict one, and were checked in a headless browser for violations. The policy is applied by the app's server today; a production host must send the same headers (a task for the deployment slice).

`cutpay.js` is published twice: as `/v1/cutpay.js`, which changes with each release, and pinned at `/v1/<version>/cutpay.js` with a `.sri` file holding its `sha384` hash. A merchant who pins the versioned file with `integrity=` and `crossorigin` is protected if our server is ever made to serve a different file. The docs say to do so.

### Encrypt what is personal and not searched
Bank account numbers are encrypted with HashiCorp Vault's transit engine through a small `FieldCipher` (`libs/common-crypto`): the application sends the value to Vault and stores `vault:v1:...`, so a copy of the database is not enough, and Vault can rotate the key and rewrap old values. The key is derived per owner (the merchant or connected account id is the context), so a ciphertext copied to another row will not decrypt. The country and last four digits, which responses already showed, sit in their own columns, so listing accounts never calls Vault. Rows stored before are encrypted in place at start-up. A local AES-GCM implementation exists for tests; there is deliberately no silent default, and a service must name its provider.

Not everything is encrypted. Customer emails and names are searched, sorted and shown in lists; encrypting them would break that, or need a blind index. Choosing what not to encrypt is part of the lesson, and it is written down in the threat model.

### Check continuously
A `Security` workflow runs on every push and weekly: gitleaks over the full history, `pnpm audit` for production dependencies, OWASP Dependency-Check over the built jars, and a ZAP baseline scan of the production checkout build (clickjacking, sniffing and CSP rules fail the build). Dependabot proposes updates. Findings so far: three secret-scanner hits, all test vectors and placeholders, allow-listed by path with reasons.

## Not done, and why
- **Mutual TLS between services.** Needs certificates and a mesh or an operator; it belongs with the Kubernetes slice. The threat model lists it as the top gap.
- **The full IBAN still travels in the connected-account event** to the payouts service. Fixing it means changing the event's contract; recorded as a gap.
- **Dependency-Check is untested here.** It needs a large vulnerability database download that is only practical with an NVD key in CI; the workflow was linted and the other three jobs were run locally.
- **A risk engine / CAPTCHA** in front of confirmation, the real answer to card testing. The gateway's per-address limit is the stand-in.

## Consequences
- Running the payouts service needs Vault (added to the compose file in dev mode, with a fixed root token). Its data is lost when the container stops, and so is anything encrypted with its keys: for a dev stack that is fine; it would not be for anything real.
- Every read that needs the full IBAN costs a call to Vault; today only the settlement job does.
