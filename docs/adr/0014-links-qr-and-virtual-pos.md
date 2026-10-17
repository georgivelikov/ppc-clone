# 0014 — Payment links, QR codes and the virtual terminal

- **Status:** Accepted
- **Date:** 2026-10-18

## Context
Phase 4 lets a merchant get paid without a website: a shareable link, a printed QR code, a full-screen QR on a tablet, and a form to key in a card for a phone order. Three of these have real design questions: how a link with a limited number of uses survives many customers at once, how a screen learns "paid" quickly, and why a keyed-in card skips 3-D Secure.

## Decision

### A `links` service, and payments stays the only place a payment is created
Links live in their own service and database (`links`). A customer opening a link calls `POST /links/{slug}/sessions`; `links` prices the order itself (the browser never sends a total, only the chosen amount for customer-chooses links, and a quantity), then asks `payments` to create a normal payment through an **internal** endpoint (`POST /internal/payments`, not routed by the gateway). From there it is an ordinary hosted checkout. The payment carries `link_id`, `link_slug` and `link_reservation_id` in its metadata, which is how the result finds its way back.

### Limited uses are protected by a reservation, decided by one atomic UPDATE
`max_uses` must hold however many customers arrive together. A read of "uses left" followed by a write would let two customers both see one left. Instead, opening the checkout runs a single statement:

```sql
UPDATE payment_links SET reserved = reserved + 1
WHERE id = :id AND active AND (expires_at IS NULL OR expires_at > :now)
  AND (max_uses IS NULL OR uses_count + reserved < max_uses)
```

Zero rows updated means someone else took the last use and the customer is told the link is no longer available, before any payment exists. The database serialises the competing updates on the link's row, so exactly one wins. A row in `link_reservations` records who holds the use.

The held use is resolved by the payment's outcome, consumed from `payment.events` with an inbox check (so redelivery changes nothing):

- **Succeeded:** the reservation becomes a real use (`uses_count + 1`, `reserved - 1`, `amount_collected` grows). If that was the last use the link deactivates and `PaymentLinkDeactivated` (reason `max_uses_reached`) is published.
- **Failed or canceled:** the use is given back, so the next customer can pay. A failed payment is final in `payments`, so a customer retrying gets a new session and a new reservation.
- **Abandoned:** a job gives back reservations older than 15 minutes (`cutpay.links.reservation-ttl`).
- **Payments cannot be started:** if the call to `payments` fails, the reservation is released at once.

The concurrency test fires 12 simultaneous sessions at a one-use link and asserts one `201`, eleven `409` and that the losers never reached `payments`.

*Known gap.* A customer who pays after their reservation timed out has already had the use given back. Their money was taken, so it is counted as a use even if that takes the link past `max_uses`. Making `payments` refuse to confirm after the reservation window would close this; it is rare enough for an educational platform and is noted rather than hidden.

### Slugs
Ten random base58 characters from `SecureRandom` (about 4e17 values; no `0`, `O`, `I`, `l`). The slug is the only credential of the public endpoint, like a checkout's client secret, so it is long, random and rate limited per client address at the gateway.

### QR codes
ZXing works out which squares are dark; the pictures are drawn by our own code. That gives merchant colours, a sharp **SVG** (one rectangle per horizontal run of dark squares) and a **PNG** drawn at a whole number of pixels per square so no edge is blurred. A four-square border is added as the standard requires. The **A6 table card** is built with OpenPDF. The standard PDF fonts only cover Western European text, so other characters in a title print as `?`; embedding a font would fix it and was left out to avoid shipping one.

Colours are stored on the link (`qr_style`; one code per link, so there is no separate table). Uploading a logo into the code was not built. `GET /v1/qr?data=` draws a code for a checkout address, used by "Charge with QR", and refuses anything that does not start with our checkout address, so it is not a free QR generator.

### Following a payment live
`GET /v1/payment_intents/{id}/stream` is Server-Sent Events: the current status first, then every change, closing at an end state. The payment is opened with the caller's own scope, so a stream can only be opened on one's own payments.

Subscribers live in the memory of the instance they connected to. A change made by that instance is announced right after its transaction commits. A change made by *another* instance arrives through Kafka: every instance reads `payment.events` under its own random group id from "latest", so each hears everything. This scales without shared state for a handful of instances; a large fleet would put Redis pub/sub in between so each instance reads one channel instead of the whole topic.

The browser uses `fetch` with a streaming body rather than `EventSource`, which cannot send the `Authorization` header. If the connection drops, the screen reads the payment directly and reopens the stream, so a bad network never leaves it stuck on "waiting". Errors from this endpoint are JSON even when the client asked for `text/event-stream`; the shared exception handler now always answers errors as JSON.

### The virtual terminal and MOTO
`POST /v1/terminal/charges` creates an ordinary payment with `channel = moto`. The card is typed into the same iframe card element as any checkout, so the dashboard's code never sees the number. `payments` passes the channel to the acquirer; the simulated acquirer then skips the 3-D Secure challenge it would otherwise ask for.

Why: 3-D Secure proves the cardholder is present and approving. A merchant typing in a number from a phone call has no cardholder at a screen to answer, and mail/telephone orders fall outside the European strong customer authentication rules (an exemption for the transaction type, not for a particular customer). The price of the exemption is liability: such payments are easier to dispute, so the terminal is for signed-in team members only. `/v1/terminal` is in the gateway's team-only list, so API keys of every type are refused. Restricting it to owners and admins waits for roles in phase 8.

## Consequences
- A link takes a reservation per customer even when it has no limit. Unlimited links therefore also update the link row (`reserved`), so one extremely popular link serialises on its row. A counter in Redis or sharded counters would fix it; not needed at this scale.
- Revenue on a link counts what was paid and is not reduced by refunds yet.
- A link's price, currency and type are fixed for its life, so a customer who opened it before an edit cannot be charged a different price than the one they saw.
- Link events go to webhooks as `payment_link.created` and `payment_link.deactivated` (reason `manual`, `max_uses_reached` or `expired`).
