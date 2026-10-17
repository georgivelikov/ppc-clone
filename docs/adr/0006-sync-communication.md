# 0006 — REST for synchronous service-to-service calls

- **Status:** Accepted
- **Date:** 2026-10-17

## Context
Some calls must be synchronous, for example `payments` asking `acquirer-sim` to authorise a card.

## Decision
Synchronous calls use HTTP/JSON with Spring's `RestClient`. Every outbound client sets explicit connect and read timeouts. Resilience4j circuit breakers are added where a dependency can fail (from phase 1).

## Consequences
- Everything uses one protocol, which is easy to debug with curl and to stub with WireMock.
- gRPC is not part of this project.
