# 0012 — The card element and the checkout SDK

- **Status:** Accepted
- **Date:** 2026-10-17

## Context
Merchants want to embed card entry in their own pages (`elements.create('card').mount('#card')`) without the card number ever touching their servers or even their page's JavaScript. That is what keeps them out of the heaviest PCI scope.

## Decision
- **The card inputs are an iframe served by `apps/checkout`** at `/elements/card`. The number is typed into our origin, sent from there to the card vault, and exchanged for a token. The merchant's page only ever receives the *result* of a payment.
- **The iframe pays by itself.** `confirmPayment` sends a `confirm` message with the payment's client secret; the frame tokenizes, confirms and posts back a payment summary. Doing this inside the frame (not in the SDK) keeps the whole card path on one origin and needs no CORS on the public checkout routes. The token never leaves the frame.
- **3-D Secure** is handled by sending the customer to the hosted checkout (`/c/{client_secret}`), which already has the challenge screen and returns to the payment's `return_url`. This is the same approach real providers take for redirect-based authentication.
- **The SDK is a small, dependency-free library** (`packages/checkout-js`, Vite library mode). The UMD build is `window.Cutpay`, the function itself; the ESM build has a default export. Both are copied to `apps/checkout/public/v1/` and served as `http://localhost:5174/v1/cutpay.js`. TypeScript consumers import the named API from the package.
- **Publishable keys:** `Cutpay('pk_…')` refuses anything else and gives a pointed error for `sk_`/`rk_` keys. The key travels to the frame in the URL and on the frame's requests as a bearer token. The gateway ignores it on the public routes and rejects it everywhere else (ADR 0009), so it identifies the integration but grants nothing. The payment's client secret is the real capability.

## The postMessage rules
1. Every message carries `source: "cutpay-elements"` and `version: 1`; anything else is dropped.
2. The receiver trusts the browser's `event.origin` **and** `event.source`, never message contents.
   SDK side: origin is the checkout origin and source is the iframe's window. Frame side: origin is the `origin` the SDK put in the URL and source is `window.parent`.
3. Senders always name the target origin; `"*"` is never used.
4. The frame refuses to run if the `origin` parameter is missing or is not a plain `http(s)` origin, or if it is not framed at all.
5. No message contains card data. A test asserts that digits typed into the frame appear in no message.
6. Requests are matched by a random `requestId`; answers to unknown ids are ignored, and a request times out after 60 seconds.

## Framing headers
`/elements/*` is served with `frame-ancestors *` so any merchant can embed it. Every other page, including the hosted checkout, is served with `frame-ancestors 'none'` and `X-Frame-Options: DENY`, so it cannot be framed for click-jacking. In production the element could be narrowed to the merchant's registered domains.

## Consequences
- A malicious page can embed the frame and name itself as the parent, but it still cannot read what the customer types; it could only ask for a payment of a client secret it already holds.
- The frame needs its own small copy of the card field validation (shared with the hosted form).
- `pnpm dev` for the checkout builds the SDK first, because the browser bundle is a generated file (git-ignored).
