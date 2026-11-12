---
title: Private one-time identity challenges
---
# Private one-time identity challenges

The optional identity JDBC artifact contains a private one-time challenge lifecycle.
It implements a persistence slice of the [D14 policy](saas-identity-credentials.md),
not registration, recovery, authentication or mail delivery. Credentials, admission,
protected durable mail, provisioning, sessions and public UI remain unfinished.

## Ownership and transaction boundary

IdentityChallenge and its ID, purpose, verifier and repository are private Vernac
module definitions. The third generated module migration adds the challenge table;
the first two migrations remain unchanged. No new exports, automatic beans, DSL
keywords or compiler/core dependencies are introduced. Generated internal rows must
never be logged, serialized to public responses or published as events.

The package-private JDBC lifecycle requires the same canonical bound Spring writer
transaction as the [capacity helper](saas-identity-capacity.md). It takes the fixed
transaction-scoped budget lock before touching records. All lifecycle calls across
instances serialize through that database lock. Issuance/consumption also lock the
identity row and require pending status for verification or active status for recovery.
This does not implement recovery or activation: a trusted local callback must supply
the complete corresponding business action later. It must join this writer, respect
budget-before-record lock order and perform no remote effects or password hashing
while holding the lock. A callback must never use REQUIRES_NEW for identity changes.

Each newly persisted challenge reserves one slot. Replacement deletes the old record
and reuses its charged slot atomically. Successful consumption deletes the winning
record and releases exactly one slot in the same transaction as its local callback.
Replay/losing calls release nothing. Runtime callback failures mark the ambient JDBC
transaction rollback-only even when a caller catches the exception. Storage errors
are sanitized without retaining raw SQL causes. This is not an independent abuse
attempt counter and does not refund such attempts.

## Tokens, purpose and expiry

Tokens use SecureRandom, a separate UUIDv4 locator and a 32-byte secret encoded as
canonical unpadded Base64url: 80 ASCII characters total. Parsing rejects malformed,
oversized and noncanonical encodings before database work. Only the explicit delivery
accessor reveals the transport value; token/issue toString representations are redacted.
The table holds only a SHA-256 verifier, compared with MessageDigest.isEqual.
No plaintext secret is persisted, logged, emitted or placed in a generic outbox.

Verification expires after 24 hours; recovery after 30 minutes. The injected Clock
supplies one timestamp per decision. At now >= expiresAt, consumption is unavailable.
A 60-second resend cooldown is measured from the current challenge's creation timestamp;
replacement uses a fresh locator and secret and invalidates the earlier token. Purpose,
subject and status are server-side facts. Invalid, missing, wrong-purpose, expired,
replayed, superseded and ineligible actions all return the same internal false result.
There is no GET endpoint or automatic login. Invitation acceptance is not implemented.

## Cleanup and remaining gates

Explicit removeExpired deletes at most 500 expired records per call under the same
budget lock and releases their actual slots atomically. Time passing alone never frees
capacity. Terminal challenges are deleted immediately rather than retained as request
history. The method is an internal primitive; scheduled/indexed cleanup for the complete
identity/mail/admission assembly, pending-registration retention and activation races
remain required. No automatic cleanup job is installed by this slice.

Issuance outcomes are private composition details. Public routes must still implement
the shared capacity gate before existence-dependent branching, independent admission
budgets and neutral outcomes. Do not expose these helpers as public endpoints or wire
a direct-send shortcut. D10 encrypted retryable mail and D05 provisioning remain mandatory.
A01/A02, complete anti-abuse coverage and full-SaaS/final manual gates stay OPEN.

Required tests cover canonical/random/redacted tokens, purpose/expiry/replay, cooldown,
replacement at full capacity, actual-row release, bounded cleanup, caught-action rollback,
restart and two real Boot contexts competing for issuance, resend and consumption.
The existing original-schema upgrade and private-module import proofs include this slice.
