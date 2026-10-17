# Phase 2 — Ledger, fees, balances & payouts

## Goal
Every money movement is recorded in a **double-entry ledger** driven by Kafka events. Fees are calculated, the merchant sees **pending/available balances**, and a daily **settlement job** produces **payouts** to a (fake) bank account.

## Services / apps touched
- `backend/services/ledger` (new)
- `backend/services/payouts` (new)
- `backend/services/identity` (bank account on merchant, pricing plan)
- `frontend/apps/dashboard` (Balances, Payouts, Settings → Bank account)

## Pricing (mimics Paypercut)
| Card type | Fee |
|---|---|
| EEA consumer card | 1.29% + €0.10 |
| EEA business / non-EEA card | 2.69% + €0.10 |
| Refund | fee not returned |

Card "region/type" comes from the BIN table in `acquirer-sim` (add a few test BINs for business/non-EEA). Pricing stored per merchant (`pricing_plans`) so it can be overridden.

## Ledger model (`ledger` DB)
```
accounts(id, merchant_id NULL, livemode, type ENUM(merchant_pending, merchant_available, merchant_reserve,
         platform_fees, platform_cash, payouts_in_transit, customer_funds), currency, created_at)
journal_entries(id, event_id UNIQUE, entry_type, description, occurred_at)
postings(id, journal_entry_id FK, account_id FK, amount BIGINT /* +debit −credit */, currency)
balances(account_id PK, amount, version)  -- materialised, updated in same tx
processed_events(event_id PK)
```
Invariant: **sum(postings) per journal entry = 0** (DB check via deferred constraint trigger + app check).

Postings:
| Event | Debit | Credit |
|---|---|---|
| PaymentSucceeded (100.00, fee 1.39) | customer_funds 100.00 | merchant_pending 98.61, platform_fees 1.39 |
| RefundSucceeded (30.00) | merchant_pending/available 30.00 | customer_funds 30.00 |
| Funds become available (T+N) | merchant_pending | merchant_available |
| PayoutCreated | merchant_available | payouts_in_transit |
| PayoutPaid | payouts_in_transit | platform_cash |
| PayoutFailed | payouts_in_transit | merchant_available |

Balance can go negative (refund after payout) → show as negative, deduct from next payout.

## Payouts model (`payouts` DB)
```
bank_accounts(id, merchant_id, iban, holder_name, currency, status, created_at)
payout_schedules(merchant_id PK, interval ENUM(daily, weekly, manual), delay_days INT default 2, weekly_anchor)
payouts(id 'po_', merchant_id, livemode, amount, currency, status ENUM(pending, in_transit, paid, failed),
        arrival_date, bank_account_id, failure_code, created_at)
payout_items(payout_id, journal_entry_ref, amount)   -- reconciliation: which transactions are in which payout
```
Fake bank: IBAN ending `…0000` → `PayoutFailed (account_closed)`; else paid after 1 minute (scaled time).

## Kafka
- Consumes: `payment.events`, `refund.events` (ledger), `ledger.entries` (payouts for availability), `payout.events` (ledger)
- Produces: `ledger.entries` (`JournalEntryPosted`, `FundsAvailable`), `payout.events` (`PayoutCreated`, `PayoutPaid`, `PayoutFailed`)
- **Saga:** payment → ledger → availability → payout → ledger.

## Scheduling
- `AvailabilityJob` (ledger): moves pending → available for entries older than `delay_days`.
- `PayoutJob` (payouts): per merchant per schedule, creates payout of available balance.
- Use ShedLock (Postgres) so only one instance runs a job. Add a **simulated clock** (`Clock` bean + admin endpoint `POST /admin/clock/advance?days=1`) to test time-based flows quickly.

## REST endpoints
| Method | Path |
|---|---|
| GET | `/v1/balance` (pending, available, reserved — per currency) |
| GET | `/v1/balance_transactions` (fees, nets, sources) |
| GET/POST | `/v1/payouts`, `GET /v1/payouts/{id}` (+ items) |
| POST | `/v1/payouts` (manual instant payout, fee 1%) |
| GET/PUT | `/v1/payout_schedule` |
| GET/POST/DELETE | `/v1/bank_accounts` |

## UI screens
- **Balances:** cards for pending/available, chart of volume vs fees (Recharts), balance transactions table
- **Payouts:** list, detail with included transactions, "Pay out now"
- **Settings → Payouts:** bank account (IBAN validation with checksum), schedule
- Payment detail: show fee + net

## Tasks
1. BIN table + card type in acquirer-sim; include `card_type`, `card_region` in `PaymentSucceeded`.
2. `ledger` service: accounts auto-created on `MerchantCreated`; posting engine; invariant checks.
3. Inbox idempotency + **exactly-once processing pattern**: consume → `processed_events` insert + postings in one DB tx → commit offset.
4. Fee calculator (strategy per pricing plan), `HALF_EVEN`, unit tests with tricky amounts.
5. `payouts` service, jobs, ShedLock, simulated clock.
6. Reconciliation report endpoint: sum of payout_items == payout.amount.
7. Dashboard pages.

## Learning objectives
- Double-entry accounting for payments
- Event-driven sagas & eventual consistency
- Idempotent consumers, ordering by key, offset management
- Scheduled jobs in a multi-instance world
- Testing time-dependent logic

## Acceptance criteria
- Pay €100 with consumer card → balance pending €98.61; advance clock 2 days → available; payout job → payout €98.61 → paid.
- Replaying `payment.events` from offset 0 doesn't change balances.
- Trial balance (sum all postings) = 0 at all times — expose `/admin/trial-balance` and assert in tests.
- Tests: fee calculator unit tests, ledger property test (random event sequences keep invariants), Testcontainers saga test, Playwright `payout.spec.ts`.
