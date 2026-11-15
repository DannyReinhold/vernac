---
title: Recipient-bound account invitations through shared durable mail
---
# Account invitations

`IdentityInvitationActions` extends the browser action port. The actual IdentityJourneys
assembly issues invitations only for a currently authenticated account owner, with finite
catalog product roles and no ownership input. The recipient can be a not-yet-registered
address; no identity is created or disclosed by issuing the invitation. Normal completion
and subject-only admission denial retain the neutral request result.

Private AccountInvitation rows bind account, issuing UserId, exact canonical intended
recipient/original delivery spelling, role set, one-way verifier and seven-day deadline.
The existing platform challenge-token primitive supplies the transient secret; it is not
stored in plaintext. A distinct INVITATION purpose binds the encrypted mail context and
cannot be redeemed through verification/recovery. The thirteenth generated migration
adds only this table/references and the reviewed enum-purpose expansion. Existing twelve
histories remain unchanged. There are at most 100 stored invitations per account and
all use the existing shared actionable-challenge/storage budget.

Admitted issuance prepares encryption outside the writer, then persists invitation and
protected job atomically. The ordinary bound Brevo/Noop provider delivers the fragment
link after commit. The existing worker rechecks account/issuer identity and current
issuer ownership before sending, with its same leases/retries/fencing/erasure behavior.
A fifth fixed durable cursor removes expired/ineligible invitations, cancels their mail
jobs and refunds exactly their charged capacity. Live leased ciphertext keeps its existing
settlement/expiry accounting. No second SMTP sender or credential-free production fallback.

Acceptance requires the current nominal stamped active identity and its verified canonical
login address to match the recipient. It rechecks purpose/verifier, expiry, account,
current issuer ownership, role catalog and capacity in one writer. A fresh/restored member
is never an owner. An already active membership is idempotent and retains its exact roles
and owner fact; the invitation cannot silently escalate it. Consumption/revoke/expiry
are deterministic and replay cannot add another membership. Technical metadata bounds:
1,000 stored members per account and 100 active memberships per identity. These are not
paid-plan entitlements.

GET `/auth/invitation` is public only to scrub the fragment and explain sign-in. Anonymous
POST cannot accept. A visitor may sign in with the intended verified address and reopen
the original link; no token is saved in local/session storage or a return URL. Explicit
successful acceptance retains the current identity/session and redirects to deliberate
account selection. It never auto-selects a tenant or promotes ownership. Other actions
retain their ordinary login-after-verification/recovery semantics.

Four actual PostgreSQL proofs cover intended recipient and purpose, existing-membership
non-escalation, two-context one-winner acceptance, revoke/expiry/lost issuer ownership,
actual shared mail delivery and cleanup refunds. Seven configured-chain proofs (including
the inherited public-flow cases) verify the invitation GET/anonymous POST and preservation
of the authenticated session pending explicit account selection. All use bounded local
test providers; no real email is sent. Owner UI, actual metadata/session composition and Chromium acceptance are now recorded
in [final Identity/Auth acceptance](../development/identity-auth-acceptance-0.6.0.md).
