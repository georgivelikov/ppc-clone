# 0027 — Disputes (chargebacks)

- **Status:** Accepted
- **Date:** 2026-10-20

## Context
A cardholder can ask their bank for the money back. The merchant loses the payment unless they prove the customer got what they paid for. The platform has to take the money at once, give the merchant a chance to answer, and settle the books whichever way it ends.

## Decision

### Where it lives
A `dispute` package in the payments service, not a new service. A dispute is about one payment, needs its status and its refund rules, and talks to the same card network. Its events go out on `dispute.events` (`DisputeOpened`, `DisputeEvidenceSubmitted`, `DisputeClosed`); the ledger, the webhooks service (`charge.dispute.created`, `.updated`, `.closed`) and notifications consume them.

### How a test dispute starts
The card `4000 0000 0000 0259` is approved like any card, and the simulator tells payments (in the card's description) that its holder will dispute the payment one day after it succeeds. Payments records that when the payment succeeds, and a job opens the dispute once the payments *simulated clock* has moved a day. So a test (or a person) moves the clock, as for payouts and subscriptions.

### Lifecycle
`needs_response` (seven days to answer) → `under_review` (evidence sent; the network takes a few seconds) → `won` or `lost`. A dispute nobody answered by its deadline is lost; the merchant can also accept it (lost at once). Evidence is written text and up to five files of 2 MB. The simulated network decides from the words: evidence containing `winning_evidence` wins, anything else loses. If the network cannot be reached the dispute stays under review and is asked again.

### Money
When a dispute opens the ledger moves the disputed amount from the merchant's pending funds (or available funds, if the payment was already released) to a `disputes_held` account, and charges a fee of about 15 euros (a figure per currency) out of available funds, which may go negative until later payments cover it. Winning moves the amount back to where it came from and returns the fee. Losing moves the amount to the customer's side (`customer_funds`) and keeps the fee. A refund of a payment under dispute is refused, or the customer would be paid twice. Split payments are not handled: a dispute takes the whole amount from the merchant who owns the payment, and the connected accounts' shares are not reversed.

### Files in the database
The plan asked for object storage (MinIO). Evidence files are stored in a `bytea` column instead: they are small, limited, and need no extra container, and the download is served as an attachment of type `application/octet-stream` so a hostile file is never shown by the browser as what it claims to be. The same choice is made for report files (a later slice); it is the first thing to change if files become large.

## Consequences
- The dispute fee is a constant table in code, not a pricing plan setting.
- A dispute only opens while the payments service runs; missed days are caught up on the next pass, because the job takes everything due.
- Webhook events carry the dispute's amounts and deadline but not the evidence text.
