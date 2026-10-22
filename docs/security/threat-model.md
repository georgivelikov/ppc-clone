# Threat model: checkout and API

A STRIDE review of the two surfaces where an attacker gains most: the page that takes a card (hosted checkout and the embedded card field) and the merchant API that moves money. Written 2026-10-22. It lists what is in place and, as plainly, what is not. "Not done" entries are real gaps in a project that moves no real money; in a real deployment each would be a ticket.

## What is being protected
| Asset | Why it matters |
|---|---|
| Card number, expiry, CVC | Fraud; PCI scope (see [pci-dss.md](pci-dss.md)) |
| Payment state and the ledger | Money: a payment marked paid that was not, a refund that was not asked for |
| Merchant API keys and sessions | Whoever holds one acts as the merchant |
| Bank account numbers (IBAN) | Payouts go there; personal data |
| The checkout page and `cutpay.js` | Code that runs on pages where cards are typed |
| Staff access | Can freeze payouts, adjust balances, redrive events |

## Trust boundaries
1. **Internet to gateway** (merchant API, dashboard, back-office, public checkout paths).
2. **Customer browser to checkout origin** (the page, the card iframe, `cutpay.js` on the merchant's page).
3. **Gateway to services**, and **service to service** (HTTP and Kafka), on a private network.
4. **Services to their databases, Kafka, Vault.**
5. **Platform to the outside** (webhooks to merchant URLs; the acquirer and bank, simulated here).

## Checkout (boundaries 1 and 2)

| | Threat | Mitigation in place | Gap |
|---|---|---|---|
| **S**poofing | A look-alike page collects cards | Hosted checkout and card field live on Cutpay's own origin; the merchant's page only embeds an iframe | Brand/domain monitoring is not something code can do |
| | A client secret guessed or stolen to confirm someone else's payment | Secret is long random, bound to one payment, only works while the payment is open; confirmation is rate-limited per address at the gateway | A secret in the address bar can be shoulder-surfed: unavoidable for a redirect flow |
| | Merchant page impersonates the card field | The card field posts a result to the parent only, never the card; the parent origin is passed explicitly and checked | |
| **T**ampering | Amount changed in the browser | The amount lives on the server with the payment; the browser sends only a token and an email | |
| | `cutpay.js` replaced on our server so every merchant page skims cards | Versioned copy with a published SRI hash (`/v1/<version>/cutpay.js.sri`); merchants who pin it refuse a modified file | Merchants using the floating `/v1/cutpay.js` get no such protection; documented as "pin it" |
| | Script injected into the checkout page (XSS) | React escapes by default; strict CSP `script-src 'self'`, no inline script in production, `object-src 'none'`, `base-uri 'none'` | CSP is applied by the Vite server/preview; a production host (nginx, CDN) must send the same headers: the Helm chart in the deployment slice has to carry them |
| | Injected script sends card data elsewhere | `connect-src 'self'`, `form-action 'self'`: an injected script can neither load a remote skimmer nor post to another origin | `img-src` is `self data:`, so an `<img>` beacon to another host is also blocked; DNS prefetch is not |
| **R**epudiation | Customer says they did not pay | Every attempt is stored with the acquirer reference, events carry trace IDs, the ledger is append-only, and three-way reconciliation (ADR 0032) compares payments, ledger and acquirer file | 3-D Secure / strong customer authentication is simulated, so there is no liability shift to model |
| **I**nformation disclosure | Card number in logs, events, or the database | The number goes to the vault and is exchanged for a token; payments see only the token, brand, last four and fingerprint. Logs are structured and do not log request bodies | The vault (acquirer-sim here) stores test cards in memory; a real vault is the PCI-scoped component |
| | Payment secret leaks via Referer | `Referrer-Policy: no-referrer` on all checkout responses | |
| | Page framed for click-jacking | `frame-ancestors 'none'` and `X-Frame-Options: DENY` on every page except the card field | The card field is frameable by anyone by design; it holds only its own input |
| **D**enial of service | Brute-forcing card numbers (card testing) | Gateway limits public checkout and vault paths to 10 requests a second per address. | A distributed attack from many addresses, and bot detection, are not covered: this is where a real PSP puts a risk engine and a CAPTCHA |
| | Exhausting payments by creating sessions | API calls need a key; limits at the gateway are per client address, not per merchant | No per-merchant spend/velocity limits |
| **E**levation | Test-mode key used on live payments | Keys carry a mode; every query filters on it; the JWT claim and the request must agree | |

## Merchant API (boundaries 1 and 3)

| | Threat | Mitigation in place | Gap |
|---|---|---|---|
| **S** | Stolen API key | Keys are hashed at rest, shown once, revocable, and carry scopes; changes to keys and team access go to the audit log | No IP allow-listing per key |
| | Stolen dashboard session | Short-lived access token in memory; refresh token in an `HttpOnly`, `SameSite=Strict` cookie scoped to `/auth`; optional TOTP; sign-in is rate-limited (5/s per address) | The dashboard keeps no long-term token in `localStorage`, so XSS cannot steal a refresh token, but an XSS in the dashboard can still act as the user while it is open |
| | Forged webhooks sent to a merchant | HMAC signatures with a timestamp and per-endpoint secret; tolerance window rejects replays | Secret rotation exists; there is no automatic forced rotation |
| **T** | Replaying a request (double charge) | `Idempotency-Key` on creating requests; consumers deduplicate on event id | |
| | A merchant reading or changing another merchant's data | The merchant comes from the verified token, never from the request; every query filters on it; see the cross-merchant tests in each service | A single missing filter would break this: it is the most valuable property to test again whenever an endpoint is added |
| | Direct access to internal services | Services accept only tokens signed by the identity service; internal endpoints sit under `/internal` and are not routed by the gateway | No mutual TLS between services; on a flat network anything that reaches a service port can call its internal endpoints. A service mesh or network policies (deployment slice) close this |
| **R** | Staff abuse | Staff actions are audited; four-eyes approval for balance adjustments; read access for any staff, write for admins only | Staff accounts are in the same system as merchants' (separate realm); no hardware-key requirement |
| **I** | Error messages leak internals | A single error shape; stack traces are not returned; actuator is not exposed through the gateway | Actuator ports are open on each service on the private network |
| | PII in the database | IBANs are encrypted with Vault transit (ADR 0034), bound to the owning account; only the country and last four digits are kept in clear. Customer emails and names are **not** encrypted: they are searched and sorted, which encryption would break, and the lesson is to choose what to encrypt | Encrypting the email would need a blind index (HMAC of the normalised address) for search: not built |
| | IBAN in events | The connected-account event still carries the full IBAN to the payouts service | Should carry a reference and let payouts fetch it, or carry the ciphertext; recorded as a known gap |
| | Secrets in the repository | Secret scanning of the full history in CI (gitleaks); the three findings so far are test vectors and are allow-listed with reasons | Dev passwords and the dev Vault root token are in `docker-compose.yml` and `application.yml` by design; they only exist locally |
| **D** | API flooding | Gateway rate limits; connection pools bounded | See `docs/perf`: one laptop saturates near 80 confirms/s; no autoscaling yet |
| | Webhook endpoint used to attack internal hosts (SSRF) | Live endpoints must be https and are refused if the name resolves to a private, loopback or link-local address, checked again at delivery time (so DNS that changes later is caught) | DNS rebinding between check and connect is narrow but not impossible; pinning the resolved address in the HTTP client would close it |
| **E** | Role escalation inside a merchant | Roles on the token; write endpoints call `requireWrite`; team management is owner-only | |

## Supply chain
| Threat | Mitigation | Gap |
|---|---|---|
| Vulnerable dependency | `pnpm audit` and OWASP Dependency-Check on the built jars on every push and weekly; Dependabot PRs | Dependency-Check needs an NVD key to be fast; findings above CVSS 7 fail the job, lower ones are reports |
| Malicious dependency | Lockfile with `--frozen-lockfile`; Gradle versions from the Spring Boot BOM | No artifact signature verification, no SBOM published |
| Compromised CI action | Actions pinned to major versions | Not pinned to commit SHAs |

## Verified in this review
- Strict CSP on the production build of the checkout loads and renders the page with no violations (headless Chromium).
- ZAP baseline against that build: no warnings or failures on the passive rules; clickjacking, content-type sniffing and CSP rules are set to fail in CI (`.zap/rules.tsv`).
- Secret scan of the full git history: no findings beyond three allow-listed test values.
- `pnpm audit --prod`: no known vulnerabilities.

## Highest-value next steps
1. Mutual TLS or network policies between services (boundary 3).
2. Stop sending the full IBAN in the connected-account event.
3. A velocity/risk engine in front of confirmation (the real answer to card testing).
4. Pin CI actions to SHAs and publish an SBOM.
