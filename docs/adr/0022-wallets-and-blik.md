# 0022 — Wallets and BLIK: more ways to pay on one payment

- **Status:** Accepted
- **Date:** 2026-10-19

## Context
A payment was paid by card, or financed by an instalment provider ([ADR 0019](0019-bnpl-applications-and-settlement-of-the-payment.md)). Phase 7 adds Apple Pay and Google Pay, which are cards presented by a wallet, and BLIK, which is not a card at all: the customer types a code, then confirms in their banking app, so the answer arrives later and from somewhere else.

## Decision

### A wallet is a card with a different way of getting the token
Real wallets need certificates and registered merchants, so the wallet is a pretend one (`acquirer-sim`, `GET /wallet/cards`, `POST /wallet/authenticate`). The customer picks a stored test card and confirms on a pretend device; the wallet answers with what a real one does: a device token (a DPAN that is not the card's number), a cryptogram and an ECI. The token goes into the same vault as a typed card, marked with the wallet that made it, so everything after it is the card flow: `payments` describes the token, authorises it and settles it.

Two things differ. The issuer does not ask for 3-D Secure, because the wallet authenticated the customer on their device (strong customer authentication is satisfied). And the payment records the wallet (`apple_pay`, `google_pay`) as its method, on the intent and on the attempt, so the merchant sees it and the reports can split by it. The fee is the card's: the ledger prices a wallet payment by the card behind it, because that is what the real network charges. A wallet token is not saved as a card for later charges (`setup_future_usage` is ignored for it), since it belongs to the wallet.

The method is decided by the token, not by what the checkout claims: `payments` reads the wallet type from the vault, so a customer cannot get a card exempted from 3-D Secure by saying they used a wallet.

### BLIK is an asynchronous method, shaped like 3-D Secure
Charging a BLIK code is two steps. The bank first accepts or refuses the code in the reply; an accepted code becomes a request in the customer's bank app, and the customer's answer reaches `payments` by a call back (`POST /internal/blik/callback`), exactly as the 3-D Secure answer does. The payment goes `requires_action` while it waits, with a next action of type `blik`, and the checkout shows "Confirm in your banking app" and asks the server every second.

- A code that is not valid does **not** change the payment: it is refused before the payment moves to `processing`, so the customer can type another. (A failed payment is final; a typo should not end one.) The price of this order is a request in the bank that nobody will answer when the payment then turns out to be already taken; the callback for a request nobody knows is ignored.
- A decline from the bank, or a request rejected in the app, fails the payment. An approval completes it, through the same `approve` as a card, so a retried callback finds the payment already moved on and does nothing.
- BLIK is for payments in PLN that are taken at once. A held payment cannot use it, because the money is taken when the customer confirms.
- Refunds go to the acquirer like a card's, so the simulator records the amount an approved request took.
- The simulator: `777123` waits for the bank app (approved by itself after a few seconds, so nobody is left waiting; `/sim/blik-app` lets a person approve or reject it first), `000000` is declined, other six digits are not a code.
- Price: 1.29% + PLN 0.25. It is a constant of the ledger (not part of the merchant's card plan), and a fee never exceeds the payment.

### One list of methods per payment, and the merchant's switches
`PaymentMethodRules.available(currency, capture, disabled)` is the only place that says what a checkout offers; `GET /checkout/sessions/{secret}/methods` returns it and the confirm endpoint enforces it. A merchant can switch wallets and BLIK off (`payment_method_settings`: only the exceptions are stored, so a new method is on for everybody until switched off). Cards cannot be switched off, so no checkout is left without a way to pay. Instalments stay in the BNPL service's own settings.

The checkout keeps the card form in the same place of the page whether or not the list of methods has arrived, so what the customer already typed is not lost when the tabs appear.

## Consequences
- A new method is a rule in `PaymentMethodRules`, a panel in the checkout and, if it has its own price, a line in the ledger. A method that answers later (like BLIK) uses the `requires_action` state and a call back.
- A BLIK request that is never answered would leave a payment in `requires_action`. The simulator always answers; a real one would expire the request, and a job here would then fail the payment. Not built.
- The wallets are not Apple's or Google's: the sheet is generic on purpose, with a note saying so.
