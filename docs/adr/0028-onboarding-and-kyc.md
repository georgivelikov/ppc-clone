# 0028 — Onboarding and KYC

- **Status:** Accepted
- **Date:** 2026-10-20

## Context
A business must be checked before it takes real money: who it is, who owns it, whether those people are real and not on a sanctions list, where payouts go. Until then it works in test mode. The checks come from outside providers, which this project replaces with a pretend one.

## Decision

### Where it lives
An `onboarding` package in the identity service, because the status it decides is the merchant's own (`merchants.status`: `pending_kyc` → `in_review` → `active`, `rejected` or `needs_more_info`). The working data is in three tables: `onboarding_applications` (answers as JSON, risk, decision), `persons` (representatives, owners of 25% or more, directors, each with a document to check) and `documents` (ID photos, selfies, proof of the bank account).

### The pretend provider
`backend/services/kyc-sim` stands in for a company register and an identity-verification provider, with no database. A registration number in the right format for the country (EIK, CUI, KRS and so on) returns a made-up but stable company; a number ending in `000` is not found and `999` is dissolved. A verification is answered after a few seconds by the last digit of the document number: `0` rejects, `9` asks for a better photo, anything else approves. Screening looks the name up in two tiny lists (sanctioned, politically exposed).

### The flow
The wizard saves each step (business with a register lookup, people, documents, bank account with an IBAN check, a description and expected volume). Submitting needs everything filled in, the terms accepted and the name typed as a signature. It then screens every person, starts a verification for every document that is not already approved, scores the risk and moves to `in_review`. A job asks the provider for answers every few seconds and decides applications that need no person: any rejected document rejects the application, any unclear one asks for more information (the person edits and submits again; approved documents are not checked twice), and when all are approved the business becomes `active`. Every decision is an `ApplicationDecided` event (the notifications service emails it), a `MerchantStatusChanged` event and an audit entry.

### Risk
A few readable rules add up to a score: a restricted industry (gambling, crypto) +50, outside the home markets +20, expected sales over 100,000 a month +30 (over 1,000,000 +60), sole trader +5, a politically exposed person +40, a person on a sanctions list +100, a dissolved company +60. From 60 up the application waits for a person at Cutpay (the back-office) whatever the checks say. The rules are in one class with a table-style test.

### What "test mode only" means
Creating a live API key is refused until the business is `active` (and, for owners and admins, until two-factor sign-in is on). The dashboard keeps showing the live view, empty, with a banner saying where the application stands. Other services do not check the status yet: a payment made through the dashboard in live mode is not blocked.

## Consequences
- Documents are in the database like evidence files (ADR 0027), downloaded as attachments and never shown inline.
- Real KYC has sanctions screening as a continuing duty, ongoing monitoring and many more document types; this does one pass at submission.
- The expected volume is asked for in euros whatever the business settles in.
