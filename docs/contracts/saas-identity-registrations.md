---
title: Private pending identity registration lifetime
---
# Private pending identity registration lifetime

Status: implemented in **0.6.0-SNAPSHOT** as a private optional JDBC primitive.
This is not a public registration flow, mail queue or
provisioning implementation. The [credential policy](saas-identity-credentials.md)
remains authoritative; all public journey and final SaaS gates remain OPEN.

## Ownership and allocation

`JdbcIdentityRegistrations` is package-private, explicitly constructed with JDBC
and an injected Clock. It adds no exported Vernac declarations, automatic beans,
HTTP routes, authentication or tenant scope. `IdentityPendingRegistration` and its
repository are private generated models in the existing identity provider module.
The seventh migration introduced the lifetime; the eighth adds owned addresses.
The first seven SQL/snapshot histories remain unchanged.

A trusted `request(LoginAddress)` allocates a fresh nominal UserId, a PENDING_VERIFICATION identity
with security epoch zero, and one pending-lifetime row in the same writer transaction.
It first reserves one PENDING_REGISTRATIONS slot under the existing deployment budget
lock. Capacity denial creates no identity or auxiliary row. Failed work or caller
rollback removes the newly allocated rows and restores the slot. Time passing alone never refunds it.
The private [owned address](saas-identity-login-ownership.md) is now created with it;
no password, challenge or delivery job is created. Repeated requests reuse an unexpired
pending owner without overwriting its delivery spelling or deadline. Capacity is reserved
before key lookup, including when the address is already known.

The immutable deadline is exactly seven days from the injected first-creation instant,
including nanoseconds. `pending()` checks identity status and `now < deadline` under
the same lock; it never renews the deadline. There is deliberately no resend/extension
setter. Ordinary generated factories are private implementation mechanisms, not an
alternative standard registration API. Existing identities created by older internal
fixtures are not automatically converted or counted as new pending registrations.

## Completion and rollback

`complete(subject, encoded, provision)` belongs only inside a trusted verification
challenge-consumption action. Its existence is not proof of mailbox ownership: the
caller must first prove the current purpose-bound challenge using the existing
[challenge lifecycle](saas-identity-challenges.md). Encode the chosen password through
the approved bounded Spring password work before opening the writer transaction.

The helper checks pending status/deadline and requires an owned address, uses the existing private credential
persistence to enroll the approved encoding, activates the identity, removes the
pending row and releases its slot. The local provisioning callback joins this same
writer transaction. It must not open an independent transaction or perform remote
side effects. Provisioning of accounts, tenants and owner membership is still future
work; an empty test callback is not end-to-end registration acceptance.

A false completion result means unavailable, never successful verification. A composing
challenge-consumption callback must reject it and roll back rather than consuming a
challenge without completing registration. Callback exceptions mark the JDBC resource
rollback-only even if caught by the caller; Spring rejects a later commit. The tested
composition restores challenge, credential, identity state and both capacity counters
on failure. Successful completion creates no session and cannot be repeated.

## Bounded abandoned-registration cleanup

`sweep(after, batchSize)` accepts 1–500 candidates and scans the pending table by its
existing primary-key index: optional `id > after`, ordered by id, bounded LIMIT.
Its private result reports inspected/removed counts and the next UUID cursor, or null
to start a new sweep. The caller must keep advancing even when no expired row is found.
An exactly full final batch needs one empty batch to wrap. Inserts behind the cursor
are handled in the next sweep.

The caller owns periodic scheduling and durable cursor/checkpoint management. Neither
is installed by this PR. A restarted caller can safely begin another sweep, but repeated
restarts can delay later keys. The eventual assembly must run complete sweeps at least
every five minutes, measure lag and prove adequate throughput before public acceptance.
Candidate scanning is bounded/indexed; dependent challenge lookups use the existing
private subject/purpose queries and make no new query-index performance guarantee.

Cleanup and completion share the capacity transaction lock before locking an identity.
At or after the immutable deadline cleanup deletes the pending identity, its pending
row, owned address, any stored credential and current private challenges, refunding only rows actually
deleted in that transaction. Challenge slots remain charged until those deletes commit.
There are currently only verification/recovery purposes; protected mail records are
not yet implemented. Their cancellation/deletion/accounting must be composed before
a public flow ships. Address ownership now joins this same cleanup transaction.

Cleanup never deletes ACTIVE, DISABLED or CLOSED identities. An inconsistent pending row
attached to such an identity, or an absent identity, produces a sanitized operational
failure and rolls back the whole batch instead of silently repairing accounting.
Database failures also roll back every dependent delete/refund. Competing cleaners cannot
double-release; activation either removes the pending marker first and survives, or
cleanup wins and the old subject cannot complete. A later registration would use a fresh
UserId; this primitive does not reuse subjects or transfer ownership.

## Boundaries and next work

[Login-address ownership](saas-identity-login-ownership.md) now composes canonical
keys, original delivery spelling, exact database uniqueness and pending cleanup.
Protected delivery, provisioning, periodic cleanup scheduling and public ingress
remain unfinished. No address is inferred for old private identity/lifetime rows.
No language syntax is introduced in this slice.

Shared public admission/capacity gates still precede existence-dependent branches. This
helper does not orchestrate public neutral outcomes, delivery/backlog reservations,
challenge supersession, session creation or account provisioning. Private primitive tests
must not be reported as any of those complete flows.

## Evidence

Restricted-role PostgreSQL tests using the packaged module prove exact deadline/restart,
no renewal, a two-context last-slot race, allocation rollback, challenge/credential/
activation/provisioning rollback, successful one-time completion, bounded cursor scans,
dependent refunds, activation-versus-cleanup races and database-failure/inconsistent-state
rollback. CI/release tooling requires these named cases and rejects skipped proofs.
