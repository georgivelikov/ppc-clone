# 0017 — The BNPL provider simulator

- **Status:** Accepted
- **Date:** 2026-10-18

## Context
Phase 6 puts one integration in front of several buy-now-pay-later providers. Real providers disagree about almost everything: units, field names, whether the answer comes at once, later by callback or only when asked, and how they say no. If the simulator gave every provider the same API, the adapters in the bnpl service would be empty and the lesson lost.

## Decision
`bnpl-sim` (port 8089) is one process with four providers under four path prefixes. They differ on purpose:

| Provider | Style | Units | Answer | Limits | Merchant fee |
|---|---|---|---|---|---|
| SplitNow `/splitnow/v1` | REST, snake_case JSON | cents | in the response | EUR 50 to 1,500 | 3.5% |
| Lendo `/lendo/api` | form-encoded requests, JSON replies | decimal euros as strings | later, as a signed form-encoded callback; can also be polled | EUR 100 to 10,000 | 2.9% |
| KreditKo `/kreditko` | camelCase JSON, nested applicant | minor units | polled only; big loans wait in manual review | EUR 200 to 15,000 | 4.5% |
| PayLaterEU `/paylatereu/api` | one RPC endpoint with an `action`; always HTTP 200 with `ok` and `code` | decimal euros as numbers | in the response | EUR 20 to 800 | 2.5% |

Rules a test can rely on: SplitNow declines emails on its blocklist (`fraud@example.com`). Lendo scores the birthdate (`400 + (day*13 + month*29 + (year%100)*7) mod 450`) and accepts an adult with a score of 600 or more. KreditKo needs a national ID and an income, approves a loan whose income is at least three monthly payments, and sends loans above EUR 5,000 to review, which resolves by itself after a delay or through `POST /admin/kreditko/loans/{id}/decision`. PayLaterEU declines `debt@example.com`.

Lendo signs its callbacks: `X-Lendo-Signature` is the HMAC-SHA256, in hex, of the exact body with a shared secret. The adapter must check it before believing the callback.

### Demo controls
`PUT /admin/providers/{code}` changes, at runtime, an `outage` switch (503), extra `latency_ms`, `timeout_rate` (the share of calls that hang for a while and then answer 504; PayLaterEU starts at 20%) and `decision_delay_ms` (how long Lendo and KreditKo take to decide). `POST /admin/reset` forgets everything and restores the defaults. State is in memory.

## Consequences
- The simulator is a contract the adapters are tested against. Changing a shape here means changing an adapter.
- Time is real time, not the simulated clock: the providers are outside Cutpay. Tests shorten the delays with properties.
- Lendo and PayLaterEU return different decimal formats on purpose; converting them to minor units is the adapters' job.
