# 0029 — Reporting: a read model built from events

- **Status:** Accepted
- **Date:** 2026-10-20

## Context
The dashboard needs numbers that span payments, fees, refunds, payouts and disputes (volume, success rate, why payments fail, how people pay, where they give up) and files for an accountant. The facts live in several services. Asking each of them at request time would be slow and would mean new endpoints in all of them; reading their databases would couple everything to their tables.

## Decision

### A projection (CQRS)
The `reporting` service owns flat tables, `fact_payments`, `fact_balance_transactions`, `fact_payouts` and `fact_disputes`, filled only from Kafka events. It writes nothing back and decides nothing, so it holds no truth that is not also in a topic.

- **Money** comes from the ledger: every balance transaction it writes is now also announced as `BalanceTransactionRecorded` on `ledger.entries`, with gross, fee and net. A report and the balance page therefore cannot disagree about what a payment cost.
- **Payments** (opened, tried, succeeded, failed, method, decline code), **payouts** and **disputes** come from their own topics.
- No customer details are copied into the read model.

### Safe in any order, safe twice
Each row remembers the time of the last event that changed it. An older event cannot overwrite a newer one, and dates that only move one way (when the checkout opened, the first attempt, the success) keep the earliest. Together with the inbox, replaying events is harmless.

### Rebuilding
`POST /admin/rebuild` empties the facts and replays the source topics from the beginning with a consumer of its own (no group, no commits), through the same code the live listener uses. `GET /admin/checksum` fingerprints each table (row count, sums and a hash of every row). A test rebuilds and compares the fingerprints: they are equal. The live listener keeps running during a rebuild, and what it applies is simply applied again.

### Reports
A request (`POST /v1/reporting/report_runs`: type, period, format, optional columns) is saved as `queued` and returns at once; a job builds the file and the run becomes `succeeded` or `failed`, with a `ReportRunSucceeded` event (an email to the requester, a `reporting.report_run.succeeded` webhook). Types: balance summary, payments, refunds, fees, payout reconciliation (each payout next to what the ledger took for it, with the difference), disputes, instalments, and a monthly statement for accountants. CSV and Excel are written by hand (an .xlsx is a zip of a few XML files) instead of with a library. Cells that a spreadsheet could take for a formula get an apostrophe in front, so hostile text in a payment cannot run when the file is opened.

### Download links
The file is in the database (as for dispute evidence, ADR 0027). A finished run returns a link signed with an HMAC of the run and an expiry, valid for 15 minutes; the service checks the signature in constant time and needs no token, so a link can be handed to a browser or an accountant. Reading the run again makes a new link. This is how S3 and Google Cloud Storage sign theirs.

### Who
Viewers and developers can ask for reports; support cannot see them. The overview and the reports are of the business and mode (test or live) of the caller.

## Consequences
- The numbers lag the payments by a few seconds, and the page says so.
- Anything the events do not carry cannot be reported. Fees by card type or per-day funnel would need more in the events.
- Rebuild time grows with the topics; compaction or snapshots would be the next step.
- The monthly statement reports fees as charged (payments and disputes); it does not add tax.
