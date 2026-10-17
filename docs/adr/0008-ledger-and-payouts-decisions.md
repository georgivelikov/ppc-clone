# 0008 — Ledger and payouts: where things live

- **Status:** Accepted
- **Date:** 2026-10-17

## Context
Phase 2 adds the `ledger` and `payouts` services. The plan left a few placement questions open.

## Decision
- **Pricing lives in the ledger.** It is the only service that needs a merchant's fee plan, so `identity` is unchanged.
- **Bank accounts live in `payouts`.** IBANs are validated and stored (masked on output) where they are used.
- **Settlement delay is owned by the ledger.** `payouts` publishes `PayoutScheduleChanged`; the ledger applies it to payments made afterwards.
- **`FundsAvailable` is a signed event** on `ledger.entries`; `payouts` projects it into `available_entries`. Negative amounts (refunds after release) are deducted from the next payout.
- **Fees** use basis points plus a fixed amount, rounded once with HALF_EVEN and capped at the payment amount. Card type and region come from a BIN table in `acquirer-sim` and travel on `PaymentSucceeded`.
- **`JobLock` instead of ShedLock.** A small Postgres lock with ShedLock's table layout, because ShedLock's Spring Boot 4 compatibility could not be verified. Swapping it in later means deleting one class.
- **Simulated clock** is opt-in (`cutpay.clock.simulated=true`) with `/admin/clock/advance`, never routed by the gateway.

## Consequences
- A refund can arrive before its payment; the ledger throws and Kafka retries with backoff, then dead-letters to `<topic>.dlt`.
- The ledger invariants (trial balance zero, balances match postings) are checked by an integration/property test.
