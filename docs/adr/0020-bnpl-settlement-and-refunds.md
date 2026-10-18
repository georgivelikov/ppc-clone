# 0020 — BNPL settlement and refunds

- **Status:** Accepted
- **Date:** 2026-10-20

## Context
A payment financed by a provider is complete as soon as the provider approves it ([ADR 0019](0019-bnpl-applications-and-settlement-of-the-payment.md)): the merchant is credited at once and the provider owes Cutpay. Two things follow later. The provider pays (the receivable has to be cleared), and the customer or the merchant may want money back, which a provider has to agree to because it is the one collecting the instalments.

## Decision

### Settlement
A day after approving an application (on the simulated clock) the provider is taken to have paid Cutpay. A job in `bnpl` marks the application `settled` and writes `BnplSettled`. The ledger then moves what the provider still owes from `bnpl_receivable` to `customer_funds`, the same account a card payment is booked to when it happens. Nothing about the merchant changes: they were paid at the time of the payment. The amount is worked out by the ledger from its own records (price, less the provider's fee, less the share already refunded), not taken from the event, so the event cannot make the books disagree with themselves. A settlement for a payment the ledger has not seen yet fails and is retried; a second settlement does nothing.

### Refunds go through the provider
Refunding a payment financed in instalments does not touch the card network. Payments asks `bnpl` (`POST /internal/applications/{id}/refunds`, not routed by the gateway), which stores the refund under the id payments gave it, asks the provider once to shorten or cancel the instalments and records the answer. Asking again with the same id answers what happened the first time, so a retry cannot refund twice; a refund id belongs to one application; refunds in total cannot exceed the application; a refused or failed refund frees its amount. Payments records a failed refund like any other (`refund_rejected` when the provider says no, `bnpl_unavailable` when it cannot be asked) and leaves the payment as it was.

### What the merchant gives back
The provider returns its fee in proportion to what is refunded. Refunding a third of a 300.00 payment on which the provider kept 10.50 gives the customer 100.00, returns 3.50 of the fee, and costs the merchant the 96.50 of that third they had received. The shares are taken from the running total, so a payment refunded in any number of pieces returns exactly the price less the fee, and the fee that comes back adds up to the whole fee. In the merchant's balance transactions a refund shows gross, a negative fee and net, so that gross less fee is net.

In the books the merchant's balance is debited that net amount and the credit goes to `bnpl_receivable` if the provider has not paid yet (it owes that much less) or to `customer_funds` if it has (the platform pays the provider back). The ledger keeps a `bnpl_payments` row per financed payment for this: the fee, what has been refunded and whether it is settled.

## Consequences
- The ledger needs two events from `bnpl` it did not before, and a settled payment's refunds draw on money the platform actually holds.
- The merchant's refund is smaller than the customer's by the returned fee share. This is a choice of this simulator; a real provider may keep its fee, and then the merchant would carry it.
- Refunding a financed payment is slower than a card refund, because a provider has to agree, and it can fail where a card refund usually does not.
