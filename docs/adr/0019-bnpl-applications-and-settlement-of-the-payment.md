# 0019 — BNPL applications and how they settle a payment

- **Status:** Accepted
- **Date:** 2026-10-19

## Context
After the customer picks an offer ([ADR 0018](0018-bnpl-aggregation-and-routing.md)) the provider has to decide, and the answer arrives in three different ways: in the reply, by a callback, or only when asked. Meanwhile the customer may give up, pay by card, or the provider may never answer. The payment itself belongs to the payments service, and a card decline there is final (a failed payment cannot be retried), which must not happen just because one provider said no.

## Decision

### An application is stored before the provider hears about it
`POST /checkout/{secret}/bnpl/apply` prices the chosen plan again, so the customer agrees to what the provider offers now, then stores the application with a copy of that offer (instalments, APR, total, merchant fee) and only then calls the provider. No database transaction is open during the call. There is at most one live application per payment (created, pending review or approved); declined, failed, expired and canceled ones do not count, so the customer can try another provider.

### Three ways to an answer, one place to record it
The reply, a callback and a poll all end in `settle`, which locks the application and moves it along an explicit status table (`ApplicationStatus`). An answer that does not fit where the application is now is ignored, so duplicates and late messages (a Lendo approval after the customer gave up) do no harm. Lendo calls back with a form body and an HMAC-SHA256 signature over the exact body; the adapter verifies it before anything is believed, and an unsigned or wrongly signed callback is a 401 that changes nothing. KreditKo never calls back, so a job asks about applications under review every few seconds (`pending_review` is "waiting for the provider"). Applications nobody decides within 24 hours of the (simulated) clock expire, and the provider is told to forget them.

Failures are kept apart from decisions. A timeout or an unreadable or failing provider makes the application `failed` and answers 502 `provider_unavailable`, with a different event for each (`BnplProviderTimedOut`, `BnplProviderFailed`), and does not count as a decline of the customer. A provider that looked at the application and refused it (HTTP 4xx) is a decline.

### The payment is completed by an event, and can refuse it
`bnpl` writes `BnplApproved` (with the payment, amount, currency and the provider's fee) to its outbox. Payments consumes `bnpl.events` in one transaction with an inbox record: it re-checks the payment and, if it is still unpaid, automatic-capture, not split and matches the amount, completes it (through `processing` to `succeeded`, so the state table stays the only authority), records an attempt of type `bnpl` and publishes `PaymentSucceeded` with `payment_method_type`, `bnpl_provider`, `bnpl_application_id` and `bnpl_fee`. If any of that is no longer true, for example the customer paid by card while Lendo was thinking, payments publishes `BnplPaymentRejected` instead, and `bnpl` closes the application and tells the provider to cancel it. A declined, failed or expired application does not touch the payment at all, so the customer can still pay another way. A payment never fails because a provider said no.

### The merchant is paid up front; the provider owes the platform
The ledger books a BNPL payment as: debit `bnpl_receivable` (the price less the provider's fee), credit the merchant's pending balance by the same. The provider's fee is the merchant's cost and not revenue of the platform, and no `customer_funds` or card fee appears. The merchant then sees gross, fee and net like any payment and is released and paid out on the normal schedule, so they are paid before the provider settles. The trial balance stays zero. Clearing the receivable when the provider settles, and refunds, are in [ADR 0020](0020-bnpl-settlement-and-refunds.md).

## Consequences
- The checkout needs two screens it did not have: a form for what the provider asks for (the offers list the required fields) and a waiting state for providers that decide later.
- Payment status is not changed while an application is pending. A customer who pays by card meanwhile wins, and the late approval is undone; the price is a short window in which both are alive.
- `bnpl.events`, `payment.events` and `refund.events` now have dead-letter topics in payments; a malformed BNPL event is parked instead of blocking the queue.
- Split payments and held (manual capture) payments cannot be paid in instalments.
