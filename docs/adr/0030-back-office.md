# 0030 — The back-office

- **Status:** Accepted
- **Date:** 2026-10-20

## Context
Cutpay's own people need to find a business, read and decide its application, mark it, hold its payouts, correct its balance and watch disputes, without being able to do what a business can do and without a business ever being able to do what they do.

## Decision

### A realm of their own
Staff sign in at `POST /auth/staff/login` against their own table (`staff_users`, roles `reviewer` and `admin`). Their access token names no business (`mid` is absent), carries the role `staff_reviewer` or `staff_admin` and `realm=staff`, lives eight hours and has no refresh: closing the tab signs out. Three fences keep the realms apart:

1. The gateway lets a staff token reach only `/v1/staff/**` and refuses every business role there (`RolePolicy`, unit-tested both ways). API keys are refused too.
2. The identity service refuses a staff token on anything outside `/v1/staff/**` and `/auth/**`, so a request that somehow bypassed the gateway still opens nothing of a business.
3. Each staff endpoint checks the realm and role itself.

For local use the first start creates `admin@`, `admin2@` and `reviewer@cutpay.test` with a password from configuration. That seeding is off unless `cutpay.staff.seed` is on and is meant for development only; a real system provisions staff through an identity provider with MFA.

### What staff can do
- **Find a business** by name, id or owner email; see its status, flags, application summary and recent activity.
- **Review applications**: a queue with the ones that need a person first; the application, the register's answer, the checks on each person and the uploaded documents (downloaded as attachments); approve, reject or ask for more information. Rejecting and asking need a reason, because it is what the business is told. The decision goes through the same code as the automatic one, so the same events, emails and audit entries follow.
- **Mark** a business (watchlist, high risk, chargeback risk, under investigation). Any staff member can.
- **Hold payouts** (admins). The identity service sets the flag and publishes `PayoutsFrozen`; the payouts service keeps a list and neither the schedule nor "pay out now" pays a held business. Money keeps arriving and waits. Lifting the hold releases it on the next run.
- **Adjust a balance** with four eyes (admins). One admin asks (amount, currency, reason); a different admin approves or rejects. The books change only on approval: an entry between the merchant's available funds and the platform's cash, a balance transaction of type `adjustment`, and the payouts service hears of the change. Approving your own request is refused by the service and the table has a check constraint saying the approver differs from the asker.
- **Watch disputes** of every business, read only.

### Everything is audited
Each staff action is written to the audit trail in the same transaction, under the business it concerns, with the staff member as the actor. The business sees "staff.application_approve" in its own Activity; the back-office has an Audit page of every staff action.

### The app
`frontend/apps/backoffice` is a small separate app (port 5177, English only), with its own sign-in. It keeps the staff token in `sessionStorage` for the tab.

## Consequences
- A staff session cannot be extended; after eight hours, sign in again.
- "System health" is a page of links (Kafka UI, Mailpit, each service's health endpoint), not a live dashboard.
- Adjustments are for one currency at a time and are posted into available funds; they do not check that the business has that money.
- Holding payouts does not stop a business creating a manual payout request in another service: the check is in the payouts service, which is where all payouts are made.
