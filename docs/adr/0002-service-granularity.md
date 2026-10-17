# 0002 — Add services per phase

- **Status:** Accepted
- **Date:** 2026-10-17

## Context
The target architecture has 11+ services. Creating all of them up front means a lot of empty boilerplate before anything works end to end.

## Decision
A service is created only in the phase that first needs it. Phase 0 has `gateway` and `identity`. Phase 1 adds `payments` and `acquirer-sim`, and so on (see `docs/plans`).

Shared code lives in `backend/libs/*` so new services start from conventions instead of copy-paste.

## Consequences
- Every phase ships a runnable system.
- Boundaries are decided when we understand the domain better, not on day one.
- Some later phases include small refactors that move code into a new service.
