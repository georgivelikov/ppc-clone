# PCI DSS: what would be in scope, and why tokenisation shrinks it

Cutpay handles no real cards, so none of this is a certification. It is the mapping a real deployment would need, and the reason the architecture is shaped the way it is. The standard's twelve requirements apply to every system that stores, processes or transmits cardholder data (CHD: the card number, and the name, expiry and service code when stored with it) or can affect the security of those systems. Sensitive authentication data (the CVC, full track data, PIN) must never be stored after authorisation, even encrypted.

## Where card data goes

```
customer browser --(card number, expiry, CVC)--> card field on Cutpay's origin
                                                   |  POST /vault/tokenize
                                                   v
                                          card vault (acquirer-sim here)  --> token
merchant server / page <-- token, brand, last4, fingerprint
payments service     --(token)--> acquirer  --> authorisation
```

Everything after the vault works with a token. The CVC is used for the authorisation and is not stored by any service.

## Scope by component

| Component | Sees card numbers? | In PCI scope? | Why |
|---|---|---|---|
| Card field / hosted checkout (static app) | Yes, in the browser, on Cutpay's origin | **Yes**: it delivers the script that reads the card | A compromised checkout page can skim cards; this is why it has a strict CSP, SRI for the SDK, no third-party scripts, and is built and deployed separately |
| Card vault (`acquirer-sim` stands in for it) | Yes | **Yes, fully** | The only component that stores the number. In a real system this is the smallest possible service, in its own network segment, with its own database and keys |
| Payments, ledger, payouts, reporting, webhooks, notifications | No: token, brand, last four, fingerprint only | Connected-to: they are on a network that talks to the vault, so the segmentation between them must be demonstrable | Last four and brand are not CHD on their own |
| Gateway | Passes the tokenisation request through | Yes, as a transmitter, unless tokenisation traffic bypasses it | It does not log bodies |
| Merchant's website using the card field | No | **SAQ A**-level for the merchant (the iframe/redirect case) | The reason to offer a hosted field at all: the card never reaches their server, which keeps their scope to the smallest questionnaire. Merchants who build their own card form would be SAQ A-EP or SAQ D |
| Databases, Kafka, logs, backups | No card numbers | Out of scope **if that stays true** | The cost of proving it stays true is the reason to keep numbers out of logs and events: one stray log line puts the log store in scope |

## Requirements mapped

| Requirement | Where it shows up here | What a real system adds |
|---|---|---|
| 1 Network security controls | Gateway is the only public entry; services on a private network; checkout paths rate-limited | Segmentation with firewalls/network policies around the vault; egress rules |
| 2 Secure configurations | One build convention; no default accounts in production profiles; dev tokens only in dev config | Hardened images, configuration baselines, CIS benchmarks |
| 3 Protect stored account data | No PAN outside the vault; CVC not stored; **IBANs** (not CHD, but sensitive) are encrypted with Vault transit (ADR 0034) | PAN encrypted with keys in an HSM; key custodians; documented key rotation |
| 4 Encrypt transmission | The browser/API edge assumes TLS; secure cookies flag exists | TLS 1.2+ everywhere including service to service (mTLS) |
| 5 Malware protection | Not applicable to the services | Endpoint protection on staff machines and hosts |
| 6 Secure systems and software | Dependency scanning, secret scanning, ZAP baseline, tests in CI, threat model, ADRs (see `docs/security/threat-model.md`) | Code review rules, a vulnerability SLA, WAF, annual penetration test |
| 7 Restrict access by need to know | Roles on merchant tokens; staff roles; four-eyes for balance adjustments | Role reviews every six months |
| 8 Identify and authenticate | Passwords with rate limiting, optional TOTP, short-lived tokens, `HttpOnly` `SameSite=Strict` refresh cookie | MFA mandatory for all staff and for any access to the cardholder data environment |
| 9 Physical access | Not applicable | Data-centre controls (inherited from the cloud provider) |
| 10 Log and monitor | Structured logs, traces, metrics, alerts (ADR 0031), audit events, reconciliation | Log integrity, daily review, retention of a year, time sync |
| 11 Test security regularly | Chaos experiments, reconciliation, ZAP baseline | Quarterly external scans, annual penetration tests, file-integrity monitoring |
| 12 Policies and risk | ADRs and the threat model | Written policies, awareness training, incident response plan, vendor management |

## How tokenisation reduces scope
1. **Fewer systems hold the number.** One vault instead of fourteen services, their databases, topics, logs and backups. Every system that can see a number must be assessed, patched, logged and segmented; a system that sees only a token needs none of that for card data.
2. **Tokens are useless outside the vault.** A stolen payments database yields tokens and last-four digits: nothing that can be charged or sold. (The token is single-purpose and tied to the vault; it is not derived from the number.)
3. **Merchants shrink too.** The hosted field keeps the number off the merchant's servers, so a breach of a merchant's shop does not leak cards, and their questionnaire is the shortest one.
4. **The price is the vault's burden.** The vault carries the full weight of the standard. That is the trade: concentrate the risk in one small, well-defended place instead of spreading a smaller risk across everything.

## What would change before real cards
- Replace the simulator vault with a real, assessed one (or buy it from an acquirer or a token service) and keep the numbers out of every other component, in logs and events included.
- Encrypt PANs with HSM-held keys; define key rotation and custodianship.
- Enforce mutual TLS and network policies around the vault.
- Make MFA mandatory for staff, and add a risk engine in front of confirmation.
- Get the assessment done by a Qualified Security Assessor.
