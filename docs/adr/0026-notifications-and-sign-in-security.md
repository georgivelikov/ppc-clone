# 0026 — Email through a notifications service, and the rest of signing in

- **Status:** Accepted
- **Date:** 2026-10-20

## Context
Invitations, password resets, receipts and decisions all end in an email. Doing that inside each service would put an SMTP client and a set of templates in every one of them, and a mail outage would break whatever was sending. The same slice finishes what ADR 0003 left out of sign-in: email confirmation, password reset, two-factor codes and a list of sessions.

## Decision

### One service sends the email
`backend/services/notifications` (port 8092, own database) is the only thing that talks to a mail server. Other services ask in one of two ways:

- **A command**: publish `EmailRequested` on `notification.events` with a template name, a recipient (`to`) or a business and an audience (`owners` or `all`), and the values the template uses. Identity does this for invitations, password resets and email confirmation, in the same transaction as the change (through the outbox).
- **A fact**: the service listens to `payout.events` (PayoutPaid), `payment.events` (PaymentSucceeded: a receipt to the customer, in the language of the payment, and an optional "new payment" email to the owners), and, once those services exist, `dispute.events`, `onboarding.events` and `report.events`. It keeps a list of who works at each business from the team events on `merchant.events`.

Asking and sending are two steps. The request is rendered and written to `notification_log` first, then delivered; a delivery that fails is retried by a job with a doubling delay and ends as `dead` after five attempts. A claim on each row stops two instances sending the same email, and a unique key of event and recipient plus the inbox guard make redelivered events harmless. The log is what Settings, Emails shows to owners and admins.

Templates are `{{name}}` messages in `mail/en.properties` and `mail/bg.properties`, with `[text](https://…)` for a link. Values are HTML-escaped and cannot carry a link of their own. A language without a template falls back to English.

### Preferences
Optional emails (new payment, payout, dispute, decision, report) can be switched off per person; only exceptions are stored. "New payment" is off until chosen. Password reset, confirmation and invitation emails always go.

### SMTP by hand
The sender speaks the few commands of SMTP over a socket to Mailpit, behind a `MailSender` interface the tests replace. A real provider would need TLS, a login and a library; the interface is where it would plug in.

### Tokens in the event
The invite, reset and confirmation links travel inside `EmailRequested`, so the token is on Kafka and in the notification log for as long as those are kept. The tokens are single-use and expire (one hour for a reset, two days for confirmation, seven for an invitation), and the log is private to the service, so we accepted it for a study project. A production system would send the address of a "send me this" endpoint, or keep the topic short-lived and encrypted.

### Sign-in security (identity)
- **Email confirmation**: a link after signup, resendable from the banner and Settings, Security. Nothing is locked behind it yet.
- **Password reset**: the form answers 202 whoever asks. The link works once for an hour, sets the password and ends every session.
- **Two-factor codes (TOTP, RFC 6238)**: written here (HMAC-SHA1, six digits, thirty seconds, one step of drift) and checked against the RFC's test vectors. Setup shows the key; the first correct code turns it on and shows eight recovery codes once; only their hashes are kept. Sign-in answers `totp_required` after a correct password and takes a code or a recovery code. Owners and admins need it on before they can create a live API key, which is where "required in live mode" is enforced for now.
- **Sessions**: the refresh tokens of one sign-in form a family; the access token carries its id (`sid`). Settings, Security lists the live ones, marks the current, and ends any other. Changing the password ends all but the current one.
- **Rotation versus sign-out**: replaying a refresh token that was *rotated* still revokes everything (theft). A token ended by sign-out or from the sessions list is simply refused; treating that as theft would sign you out everywhere the moment an old phone woke up.

## Consequences
- Email is eventually delivered: signup returns before the confirmation email exists, and a mail outage delays emails instead of failing requests.
- The dashboard shows an invitation link once as well, so a business can invite someone while the mail server is down.
- Two-factor secrets are stored in plain text in the identity database, like the signing keys (phase 9 moves both to a vault).
- Translated emails exist in English and Bulgarian only.
