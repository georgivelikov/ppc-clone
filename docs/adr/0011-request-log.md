# 0011 — The API request log

- **Status:** Accepted
- **Date:** 2026-10-17

## Context
Developers integrating the API need to see what their code sent and what came back (Developers, Logs). The gateway sees every call, but it has no database and must never be slowed down or broken by logging.

## Decision
- The gateway publishes one `ApiRequestLogged` event per **authenticated, routed** call to the `api.requests` topic, keyed by merchant. A global filter runs right after the identity filter, so the merchant, actor, mode and key are the verified ones, and logs once the response is finished.
- **No bodies, headers or query strings are logged.** Bodies can hold card data and personal data, and query strings can hold secrets; redacting them reliably is harder than leaving them out. What is kept: method, path, status, latency, actor (API key id or user id), actor type, idempotency key and request id.
- Publishing is fire-and-forget with short Kafka timeouts (1 s to find the broker, 5 s to deliver). If Kafka is down, entries are dropped; a log line is never worth failing or delaying a merchant call.
- The `webhooks` service stores the events in `api_requests` (one more small module in an existing service, as the plan allows, instead of a new service), idempotently, and serves `GET /v1/logs` with filters on status (`404` or `4xx`), method, path prefix and actor. A job deletes entries older than **30 days** hourly under a `JobLock`.
- The log is for signed-in team members. API keys cannot read it (`/v1/logs` is team-only in the gateway policy).
- Test and live entries are separate, like everything else; an API key sees only its own mode.

## Consequences
- Calls rejected before routing (unknown API key, bad token) are not logged because there is no merchant to attribute them to.
- Under a Kafka outage the log has gaps. That is acceptable for a debugging tool and would not be for an audit trail, which this deliberately is not.
