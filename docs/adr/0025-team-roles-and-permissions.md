# 0025 — Team, roles, permissions and the audit trail

- **Status:** Accepted
- **Date:** 2026-10-19

## Context
Until now a business was one person. Phase 8 lets an owner bring in colleagues, each able to do only what their job needs: an accountant reads, a support agent refunds, a developer builds. Three questions: where the rules are enforced, how people are invited, and how we keep a record of changes. Decision D2 asked us to revisit Keycloak when we got here.

## Decision

### Five roles, checked at two levels
`owner`, `admin`, `developer`, `viewer`, `support`. The role is a claim in the access token, as before.

- **The gateway (coarse)** decides by resource and by read or write, once, before any service is reached (`RolePolicy`, a pure function with a table-style unit test). Owners and admins can do everything. A developer writes payments, links, customers, billing, webhooks and API keys, and reads the rest, but neither refunds nor pays out. A viewer reads and never writes, and does not see keys or the team. Support reads payments and customers and can create refunds and customers. An unknown role gets nothing; a route nobody classified is read-only for everyone but owners and admins, so a forgotten route fails safe. Tokens issued for API keys (`role=api`) skip this check because their limits are the key's own scopes.
- **The services (fine)** decide what a role table cannot say: only an owner may give the admin role, an admin cannot change or remove another admin, nobody changes their own role, and the owner role can be neither given nor taken. These are in `TeamRules`, also pure and unit-tested through the integration tests.

We did not use `@PreAuthorize` with scopes copied into the token. The role is enough, a scope list per role would need to be kept in step with the table above, and the gateway is the one place every request passes.

### Invitations
An owner or admin invites an email address with a role. The service stores only a hash of a random 256-bit token, like refresh tokens and API keys, and returns the link once, in the response that created it. The link is valid for seven days and works once. A second invitation to the same address replaces the first. The person opens `/accept-invite?token=…`, sees who is asking and for what, and either makes an account (name, password of at least ten characters) or, if the address already has one, proves it with their password. Accepting signs them in. Sending the email is the job of the notifications service in a later slice; until then the dashboard shows the link to copy.

### Changes take effect at once
A role change or a removal ends the person's refresh tokens for that business, so the old role cannot be renewed. The access token already issued lives for at most 15 minutes. We accepted that window rather than checking membership on every request.

### The audit trail
Sensitive actions (invitations, joins, role changes, removals, API key creation, rolling and revocation, profile edits) write a row to `audit_events` in the same transaction as the action, and the same fact to the `audit.events` topic through the outbox. Rows are only added. Settings, Activity shows them newest first to owners and admins, with who did it, when and a short detail. Logins are not recorded yet.

### Keycloak
Not adopted. The hand-written identity service (ADR 0003) now has everything the project set out to learn from it, and moving would add a service and a login redirect without teaching anything about the money. The cost is what a real identity provider gives for free: two-factor sign-in, password reset, email verification and session lists. Those are the next slice of this phase, built here for the same reason.

## Consequences
- A new route must be put in a resource group in `KeyPolicy` or `RolePolicy`, or only owners and admins can write to it. The test table is the place to add its row.
- A person can be a member of several businesses, but the session is for one; switching is a sign-in with the other `merchant_id`. There is no switcher in the dashboard yet.
- Because the role is in the token, a demoted developer can still call the API with the old token for up to 15 minutes.
