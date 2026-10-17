# Phase 4 — Payment links, QR codes & Virtual POS

## Goal
Merchants **get paid without a website**: shareable payment links, printable QR codes, and a **Virtual POS terminal** in the dashboard for keyed-in/phone orders (MOTO).

## Services / apps touched
- `backend/services/links` (new)
- `payments` (link-originated sessions, MOTO flag)
- `apps/checkout` (link landing page), `apps/dashboard` (Links, QR, Virtual POS)

## Data model (`links` DB)
```
payment_links(id 'plink_', merchant_id, livemode, slug UNIQUE, type ENUM(fixed, customer_chooses, products),
              amount NULL, currency, min_amount, max_amount, title, description, image_url,
              collect ENUM[] (email, phone, name, address), quantity_adjustable BOOL,
              max_uses NULL, uses_count, expires_at NULL, active BOOL, after_completion ENUM(message, redirect),
              redirect_url, success_message, metadata, created_at)
link_line_items(link_id, product_name, unit_amount, quantity)
qr_codes(id, link_id FK, style JSONB /* colors, logo */, created_at)
```
Products/prices catalogue is introduced in phase 5; until then links use inline line items.

## Flows
1. Merchant creates link → URL `http://localhost:5174/l/{slug}`.
2. Customer opens → checkout fetches link → `POST /links/{slug}/sessions` creates a payment intent (with `link_id` in metadata) → normal checkout flow.
3. On `PaymentSucceeded` with `link_id` → `links` increments `uses_count` (consumer, idempotent), deactivates when `max_uses` hit.
4. Concurrency: two customers on last use → use `UPDATE … WHERE uses_count < max_uses` atomic check at session creation (reservation with TTL) — a nice race-condition exercise.

## QR codes
- Generated server-side with ZXing (PNG + SVG) at `GET /v1/payment_links/{id}/qr?format=svg&size=…`.
- Dashboard: customise colour, add logo, download, **print-ready A6 table card PDF** (OpenPDF) — mimics restaurant/shop use.
- Dynamic QR for in-person: dashboard "Charge with QR" → enter amount → full-screen QR shown on merchant's tablet; page subscribes to payment status via **SSE** and shows a big ✓ when paid.

## Virtual POS terminal
- Dashboard page: amount, currency, description, customer email, card fields (iframe element from phase 3).
- Payment created with `channel = moto` → acquirer-sim skips 3DS (MOTO is out of SCA scope) — learn about SCA exemptions.
- Receipt: email (phase 8) + printable receipt view.
- Role-restricted (owner/admin) — enforce in phase 8 roles.

## Kafka
- Consumes `payment.events` (links: usage counters).
- Produces `link.events`: `PaymentLinkCreated`, `PaymentLinkDeactivated` → public webhooks `payment_link.*`.

## REST endpoints
| Method | Path |
|---|---|
| POST/GET | `/v1/payment_links`, `GET/PATCH /v1/payment_links/{id}` |
| GET | `/v1/payment_links/{id}/qr` |
| GET | `/v1/payment_links/{id}/payments` |
| GET | `/links/{slug}` (public) |
| POST | `/links/{slug}/sessions` (public, rate-limited) |
| POST | `/v1/terminal/charges` (virtual POS) |
| GET | `/v1/payment_intents/{id}/stream` (SSE status) |

## UI screens
- **Links list** (status, uses, revenue), **Create link** wizard with live preview, **Link detail** (QR, share buttons — copy, email, WhatsApp/Viber deep links, payments made)
- **Charge with QR** full-screen mode
- **Virtual POS** page + receipt
- **Checkout**: link landing (customer-chooses-amount input, quantity selector, extra fields)

## Tasks
1. `links` service + migrations.
2. Slug generation (short, unguessable, base58).
3. Public session creation with reservation logic.
4. ZXing QR + PDF table card.
5. SSE endpoint in payments (or gateway) backed by a Kafka consumer → in-memory per-instance fan-out (discuss scaling: Redis pub/sub).
6. MOTO channel in payments + acquirer-sim.
7. Dashboard + checkout screens.

## Learning objectives
- Public, unauthenticated endpoints & abuse prevention
- Concurrency control (atomic counters, reservations)
- Server-Sent Events from Kafka
- SCA/3DS exemptions (MOTO) conceptually

## Acceptance criteria
- Link with `max_uses=1` paid by two concurrent customers → exactly one success, other sees "link no longer available".
- QR scanned on phone (same LAN, use host IP) opens checkout.
- "Charge with QR" screen flips to ✓ within 2 s of payment.
- Virtual POS payment shows `channel: moto` and no 3DS.
- Tests: concurrency test with parallel requests, Playwright `payment-link.spec.ts`, `virtual-pos.spec.ts`.

## As built
Status: built. Design decisions and trade-offs are in [ADR 0014](../adr/0014-links-qr-and-virtual-pos.md). Where the build differs from the plan above:

- **Data model.** `qr_codes` was folded into `payment_links.qr_style` (one code per link; colours only, no uploaded logo). A `link_reservations` table holds the uses taken by customers who are still paying, and `payment_links` gained `reserved` and `amount_collected`.
- **Reservation.** The atomic check happens when the customer continues from the link page (`POST /links/{slug}/sessions`), with a 15-minute time-out and a job that gives back abandoned uses. A 12-way concurrent test proves exactly one wins.
- **Links to payments.** `links` creates the payment through an internal endpoint on `payments` (`POST /internal/payments`), not through the public API.
- **Table card.** Built with OpenPDF; non-Western-European characters print as `?` because only the standard PDF fonts are used.
- **Charge with QR.** Uses the existing `POST /v1/checkout_sessions` and `GET /v1/qr?data=` (restricted to checkout addresses). Closing the screen before payment cancels the payment.
- **SSE.** `GET /v1/payment_intents/{id}/stream` fans out in memory per instance, fed by the committing transaction and by Kafka (random group id, `latest`). The dashboard uses `fetch` streaming because `EventSource` cannot send the `Authorization` header, and falls back to polling.
- **Virtual POS.** `POST /v1/terminal/charges` creates a `moto` payment; the card is entered in the phase 3 iframe element. Receipt is a printable page; the email receipt waits for phase 8. Team-only (API keys are refused); owner/admin restriction waits for phase 8 roles.
- **Not built.** Logo in the QR code, per-link payment filters beyond the paid list, refunds reducing a link's collected amount.
- **Tests.** `LinksFlowIT` (19, real Postgres and Kafka, including the race), pricing, slug and QR unit tests (the PNG is scanned back with ZXing), payments tests for MOTO, the internal endpoint and the stream, dashboard and checkout component tests, and Playwright `payment-link.spec.ts` and `virtual-pos.spec.ts`.

