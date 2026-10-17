# 0013 — The public API contract

- **Status:** Accepted
- **Date:** 2026-10-17

## Context
Merchants and the SDK depend on the shape of the public API, and the docs, the generated types and the services can all drift apart. Phase 3 needs versioning, expandable fields, consistent lists, and one description that docs and clients are built from.

## Decision
- **`docs/api/openapi.yaml` is the contract**, written by hand (OpenAPI 3.1) and covering the public endpoints and the webhook payload. Services are not scanned to produce it: a generated spec describes what the code happens to do, which is the opposite of a contract. Instead, tests keep the spec honest:
  - every documented path is routed by the gateway;
  - the event types and payment statuses equal the ones in the Java sources;
  - references resolve, operation ids are unique, path parameters are declared;
  - the checked-in TypeScript types (`packages/api-client/src/generated`, made by `openapi-typescript`) equal what the spec generates;
  - the test cards, fee tiers and fee rates on the site equal the card network's and the ledger's.
- **The API reference is rendered from the same file** with Redoc on the site, so it cannot say something the spec does not.
- **Versioning is date-based.** `Cutpay-Version: 2026-10-01` pins behaviour; without it the latest applies. The gateway refuses unknown versions (400, listing the supported ones), echoes the version used on every response and forwards it to the services, so the first breaking change can branch on it. Events carry `api_version`.
- **Expansion** uses `expand[]`. A payment's `latest_attempt` is the attempt id by default and the whole attempt with `?expand[]=latest_attempt`. Unknown expansions are ignored, so new ones can be added without breaking old callers.
- **Lists** are always `{ "object": "list", "data": [...], "has_more": bool }` and page with `limit` and `starting_after`. Where the cursor is an id that does not sort by time (events), it is resolved to a position on the server.
- **Metadata** (string key-values) is kept on payments and returned as sent.

## Consequences
- Changing an endpoint means editing the spec in the same change, or a test fails.
- The spec can still be wrong about something no test compares (a field's description, say); the contract tests catch structural drift, not every mistake.
- Only one API version exists today. The mechanism is tested; the first real version split will show what is missing.
