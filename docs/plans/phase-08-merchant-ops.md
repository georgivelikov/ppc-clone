# Phase 8 — Merchant operations: KYC onboarding, team, disputes, reports, notifications, admin

## Goal
Everything around the money: Paypercut-style **fully digital onboarding with KYC/KYB**, **team members & roles**, **disputes/chargebacks**, **reports & exports**, **email notifications**, and an internal **back-office** for Cutpay staff.

## Services / apps touched
- `identity` (onboarding, KYB, team, roles, 2FA, email verification)
- `backend/services/kyc-sim` (new — fake identity-verification & company-registry provider)
- `backend/services/notifications` (new)
- `backend/services/disputes` (new, or module in payments)
- `backend/services/reporting` (new)
- `apps/dashboard`, `frontend/apps/backoffice` (new)

## Onboarding & KYC/KYB
States: `pending_kyc → in_review → active | rejected | needs_more_info`; until `active` merchant is **test-mode only** (mirrors sandbox-before-live).

Wizard steps:
1. Business type (sole trader / company / nonprofit), country, industry (MCC list — retail, hospitality, health & beauty, transport, charity, startup — matching Paypercut's verticals)
2. Company lookup by registration number (EIK/BULSTAT format for BG, CUI for RO, KRS for PL…) → `kyc-sim` company registry returns fake data
3. Representatives & UBOs (≥25% owners)
4. ID document upload (front/back) + selfie → stored in local MinIO (S3-compatible) — **use fake/sample images only**
5. Bank account (IBAN) + proof
6. Website/product description, expected volume
7. Review & submit (e-signature of terms: checkbox + typed name)

`kyc-sim` decides: document number ending `0` → reject, ending `9` → needs more info, else approve after delay. Sanctions/PEP screening against a tiny fake list. Risk score (rules: industry, country, volume) → high risk → manual review in back-office.

```
onboarding_applications(id, merchant_id, status, step, data JSONB, risk_score, submitted_at, decided_at, decided_by)
persons(id, merchant_id, role ENUM(representative, ubo, director), name, dob, nationality, ownership_pct, verification_status)
documents(id, merchant_id, person_id NULL, type, storage_key, status, rejection_reason)
```

## Team & security
- Invite by email (token link), roles: owner, admin, developer, analyst/viewer, support (refunds only).
- Permission matrix enforced in gateway (coarse) + service (fine) via `@PreAuthorize` with scopes from JWT.
- TOTP 2FA (required for owner/admin in live mode), email verification, password reset, session list & revoke, audit log (`audit.events` topic → stored, shown in Settings → Security).
- Decision D2 revisit: migrate to Keycloak? Document in ADR either way.

## Disputes / chargebacks (simulated)
- acquirer-sim test card `4000 0000 0000 0259` → payment succeeds, then **dispute opened** 1 day later (simulated clock).
- Lifecycle: `needs_response → under_review → won | lost`; dispute fee €15; funds withdrawn on open (ledger: `merchant_available → disputes_held`), returned if won.
- Merchant submits evidence (text + files) before `evidence_due_by`; acquirer-sim decides by evidence keyword (`winning_evidence` → won).
- Webhooks: `charge.dispute.created/updated/closed`.

## Notifications
- Consumes many topics; templates (Thymeleaf/MJML) localised (phase 7); sends to **Mailpit**.
- Customer: receipts, refund confirmations, subscription reminders/failed payment, invoice emails.
- Merchant: new payment (optional), payout paid, dispute opened, webhook endpoint failing, KYC decision, team invite.
- Preferences per user; digest option. `notification_log` table, retries, DLT.

## Reporting
- Reports: balance summary, payout reconciliation (per payout itemised), payments, refunds, fees, disputes, BNPL, tax-friendly monthly statement.
- Async report runs: `POST /v1/reporting/report_runs {type, interval, columns}` → job → CSV/XLSX (Apache POI) in MinIO → signed download URL → `reporting.report_run.succeeded` webhook.
- Read model: `reporting` builds denormalised tables from Kafka events (**CQRS** projection) — rebuildable by replaying topics.
- Dashboard home becomes rich: gross volume, net, success rate, top decline reasons, payment method mix, conversion funnel (checkout opened → paid).

## Back-office (`apps/backoffice`, internal staff)
- Merchant search, KYC review queue (view documents, approve/reject/request info), risk flags, freeze payouts, manual ledger adjustment (with 4-eyes approval), dispute oversight, system health links.
- Separate staff auth realm & roles; every action audited.

## Kafka
New topics: `onboarding.events`, `dispute.events`, `audit.events`, `notification.events`, `report.events`.

## Learning objectives
- KYC/KYB/AML concepts (compliance domain knowledge)
- RBAC/permission design, 2FA, audit logging
- File storage with S3 API, signed URLs
- CQRS read models & projection rebuilds
- Async job pattern with notifications

## Acceptance criteria
- New merchant completes wizard → auto-approved → can toggle to live mode; doc ending `9` → needs-more-info email → resubmit.
- Viewer role cannot refund (403), support can.
- Dispute card → dispute appears, evidence submitted → won → funds returned; ledger consistent.
- Monthly report CSV totals match ledger.
- Dropping and rebuilding reporting DB from Kafka replay gives identical numbers.
- Tests: permission matrix tests, onboarding state machine tests, Playwright `onboarding.spec.ts`, `dispute.spec.ts`, `team.spec.ts`.
