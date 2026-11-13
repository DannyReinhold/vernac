---
title: Private canonical identity login-address ownership
---
# Private canonical identity login-address ownership

Status: implemented in **0.6.0-SNAPSHOT** as private optional JDBC persistence.
This composes [unique fields](aggregate-uniqueness.md) with the charged
[pending lifetime](saas-identity-registrations.md). It is not public registration,
mail delivery, password login, sessions or account provisioning. The
[credential/browser policy](saas-identity-credentials.md) remains authoritative.

## Stored ownership

`IdentityLoginAddress` is a private global aggregate keyed by the nominal UserId.
Its primary key allows one address row per identity. `unique IdentityLoginKey loginKey`
generates an exact C-collated PostgreSQL UNIQUE constraint: one canonical key has one
owner. The delivery spelling and key are immutable through ordinary domain behavior.
They are not new public exports, natural-key UserIds, tenant IDs or capabilities.

Both private values reuse `LoginAddress.parse`, rather than defining a second email
parser/canonicalization algorithm. The key must already be canonical; delivery must
already have the parsed spelling. Aggregate validation requires the delivery address's
canonical key to match the stored key. ASCII limits, case policy and plus/dot behavior
remain exactly those of the existing [address policy](saas-identity-credentials.md#2-email-delivery-address-and-login-key).
This is syntactic input/ownership persistence, not deliverability or mailbox proof.

The table stores the actual bounded key and delivery address for exact lookup and
trusted future delivery. These values are not pseudonymous limiter keys or plaintext
challenge secrets. Their representations and the containing generated aggregate redact
addresses; explicit getters remain sensitive trusted access. Do not log them, put them
in ordinary events/DTOs, or use them as metric labels. The module's only export remains
StoredIdentityDirectory. Logical module privacy does not authorize bypassing it through
generated internal Java repositories or direct SQL.

## Controlled request and capacity

The package-private `JdbcIdentityRegistrations.request(LoginAddress)` replaces the old
bare allocation method. The caller parses/bounds input before opening the writer and
must apply independent public admission before this business operation. The helper
requires the existing single bound writer/capacity boundary; it installs no automatic
bean, route, actor or transaction suspension.

Under the shared capacity lock it captures one authoritative Clock instant and reserves
one potential PENDING_REGISTRATIONS slot **before** looking up the key. Capacity denial
creates no identity/address/challenge/credential row. Known pending, active, disabled,
closed and unknown addresses get the same safe CAPACITY failure under pressure. This
conservative reservation can deny an existing address even though it would need no new
row; unused reservations are refunded atomically after the lookup.

| Private outcome | Stored behavior | Trusted handoff |
| --- | --- | --- |
| CREATED | Fresh UserId, pending identity, immutable seven-day marker and owned address commit together | New subject and original parsed delivery spelling |
| PENDING | Existing unexpired pending owner survives unchanged; unused slot released | Same subject and **stored** original delivery spelling |
| UNAVAILABLE | Existing active/disabled/closed or expired pending owner is retained; unused slot released | No subject/delivery access |

These outcomes are internal orchestration facts, never public response variants.
The handoff's toString is redacted. Unavailable subject/delivery getters fail safely.
Public neutral outcomes, source/global/subject throttle interpretation and technical
unavailability remain future ingress work. No mail is implied or queued by this helper.
A repeat must use the stored delivery spelling, not the latest unauthenticated input.

Repeated requests do not update delivery, creation metadata, credential, eligibility,
security epoch or the first-creation deadline. They create no request-history rows or
challenges. Plus suffixes and dots do not collapse into aliases. Two controlled contexts
share the budget lock before lookup and therefore create one owner; subsequent requests
reuse that pending subject when capacity permits. The database constraint independently
rejects duplicate canonical ownership. A conflict from an unsupported outside writer
poisons/rolls back this transaction and becomes sanitized storage failure; do not catch
it and continue writing or transfer the existing owner.

The pending slot covers one pending identity, marker and address row as a single charged
registration. Verified identities retain their address after the pending marker/slot is
removed. This budget bounds unverified registrations, not the lifetime count of legitimate
verified/closed users. No new storage-budget resource or hidden side ledger is introduced.

## Completion, retention and abandonment

Trusted challenge completion now requires the pending subject's address row. Credential
enrollment, activation, pending-slot release and local provisioning remain in one writer
transaction. Completion is not mailbox proof on its own; use the current verification
challenge and reject a false completion in the composing callback as documented by the
[pending contract](saas-identity-registrations.md). Account/tenant/owner provisioning
and protected delivery are still unfinished.

Activation keeps ownership. Disabling and closure also keep it: registration cannot
reactivate those identities, overwrite their delivery, replace their password or obtain
a new UserId for that key. A verified-address-change/reuse policy remains separate future
work. Closure does not implicitly release the login address.

Abandoned pending cleanup removes the address, private challenges/credential, pending
marker and identity atomically under the same lock as request and completion. Only actual
committed deletes refund the corresponding slots. Failed deletion restores every row
and counter; two cleaners cannot double-release. Cleanup never treats active/disabled/
closed identities as abandoned pending registrations.

Once safely deleted, the same address can register with a fresh UserId and fresh deadline.
A previous still-unexpired verification token cannot follow the new owner: its old
challenge/subject was removed, and lookup is not retargeted by email. Request racing with
expiry cleanup either sees the old expired registration as unavailable or creates a fresh
one after cleanup; cleanup cannot delete that fresh lifetime. Activation winning first
retains the original active owner and address.

The existing bounded primary-key sweep and caller-owned cursor remain. No periodic
scheduler, durable cursor owner or protected-mail cancellation is installed here. Those
must be composed and load-tested before a complete public journey ships.

## Migration and legacy internal rows

The eighth generated migration adds only the private address table, its primary key,
exact unique key and standard timestamp constraints. It advances the provider snapshot
to schema format 4. The previous seven SQL/snapshot histories remain unchanged. Module
activation applies/validates the owned migration normally; budget initialization and
registration orchestration remain explicit.

Older private identity/lifetime fixtures contained no email information. The migration
therefore invents no addresses, owners or inferred links and rewrites no identities.
Unclaimed legacy pending rows may expire through accounted cleanup; they cannot complete
standard registration without an address owner. Existing active/disabled/closed IDs and
the public directory contract remain unchanged. Attaching an address to a legacy identity
would require an explicit independently verified workflow, not a registration-time guess.

## Evidence and remaining work

Restricted-role packaged-module PostgreSQL tests prove canonical repeated requests,
original spelling/deadline preservation, alias distinctions, two-context creation and
restart, uniform capacity denial, caller/write/cleanup rollback, status/closure retention,
actual uniqueness, legacy pending cleanup, activation/cleanup and new-request/cleanup races,
and inability of an old live token to target a newly registered subject. Value tests
prove canonical/matching invariants and redaction. Required named CI/release proofs reject
missing or skipped tests.

Next: protected durable verification-mail envelopes and bounded delivery lifecycle, then
account/owner provisioning and public registration/login/session/browser integration.
No A01/A02/full-SaaS/final manual release gate is closed by this private persistence slice.
