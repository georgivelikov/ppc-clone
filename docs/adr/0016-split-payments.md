# 0016 — Split payments to connected accounts

- **Status:** Accepted
- **Date:** 2026-10-18

## Context
A marketplace or delivery platform takes one payment from the customer and owes parts of it to the sellers or restaurants on it. The platform is the merchant of record. Each seller needs its own balance and its own payouts, the books must stay balanced, and a refund must not leave the platform paying for money it never kept.

## Decision

### Who the accounts are
A **connected account** (`acct_`) belongs to one platform merchant and lives in `identity`, next to merchants. It has a name, a country and currency (the platform's default currency, so a payment and its transfers share one currency) and an IBAN. Responses show only the last four digits; the full IBAN goes only to `payouts` in the creation event. Disabling an account stops new payments naming it, but what it already earned is still paid out.

In the ledger an account is just another party: its pending and available accounts are keyed by the `acct_` id exactly like a merchant's, so releasing, payouts and negative balances needed no new concepts.

### Two ways to say how the money is shared
`transfer_data` names destinations. Either every destination has an amount and the platform keeps the rest, or there is exactly one destination without an amount together with `application_fee_amount`, and the destination gets everything else. Mixing the two is rejected (`ambiguous_split`), as are transfers that exceed the payment, duplicate or disabled destinations, other currencies, live accounts on a test payment, and manual capture. Payments checks every destination with identity before it stores anything, in one transaction with the payment, so a split is stored whole or not at all.

### One journal entry, several parties
When the payment succeeds, one balanced entry debits customer funds and credits the platform's pending balance (amount − transfers − processing fee), the fee account, and each destination's pending balance. The processing fee is computed on the whole payment and paid by the platform out of its share, so a platform that sends everything on pays the fee from its own balance (the trial balance still sums to zero). The platform sees one payment (gross, fee, net after transfers); each account sees a `transfer`.

Each party gets its own `pending_releases` row and so is released, and then paid out, on its own schedule. Two consequences are visible in the code: the release entry's event id includes the merchant, and a refund that lowers several parties' available balances emits one `FundsAvailable` per party with a party-qualified entry id (payouts keys its rows on the entry id).

### Refunds are taken back in proportion
A refund of R on a payment of G reverses `round_half_up(T_i × R / G)` from each destination, never more than it received nor more than what is left of R, and the platform covers the remainder. Reversed amounts are remembered per transfer so that many small refunds cannot take more than was sent. The fee is not returned, as for any refund. A test refunds a payment twenty times in a row and checks no account goes below zero and the books balance.

### Reading a connected account's balance
`GET /v1/balance?connected_account=acct_…` (and the same for balance transactions) reads the account's balance. The ledger learns who owns an account from the creation event, and an account of somebody else is indistinguishable from one that does not exist.

## Consequences
- Platform and account must pay out in the same currency; cross-currency splits belong with the multi-currency work of phase 7.
- Transfers are decided when the payment is created. They cannot be changed later, and a failed or canceled payment simply never sends them.
- There is no separate "transfer" object or reversal API: the ledger's balance transactions are the record.
- A platform with a negative available balance (fees exceed what it keeps) has it deducted from its next payout, like any negative balance.
