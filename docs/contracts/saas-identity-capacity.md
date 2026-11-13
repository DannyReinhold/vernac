---
title: Private identity storage-capacity foundation
---
# Private identity storage-capacity foundation

Status: implemented in **0.6.0-SNAPSHOT** inside the optional `vernac-saas-identity-jdbc`
artifact. This is the first persistent storage-budget primitive required by the
[D14 anti-abuse/storage policy](saas-identity-credentials.md#51-bounded-storage-mail-backlog-and-cleanup).
The [private challenge lifecycle](saas-identity-challenges.md) now accounts for actual
challenge rows through this primitive. The budget helper itself does not create
credentials/challenges or count actual registration/mail rows,
run cleanup, implement a rate limiter or expose public registration endpoints.

## Private model and normal migration lifecycle

The producer owns a private global IdentityCapacity aggregate, a nonnegative quantity,
a nominal technical ID and a private repository. Nothing new is exported in `.vmodule`;
StoredIdentityDirectory remains the only export of `vernac.saas.identity.jdbc`.
Consumers cannot import the private capacity definitions, and ordinary applications do
not gain a public quota/administration API. Java helper JdbcIdentityCapacity is package-private
implementation infrastructure, not an application extension SPI or automatic Spring bean.

The model uses the ordinary Vernac generator, schema snapshot and module migration
history. The second migration adds `"org.vernac.saas.identity.persistence"."IdentityCapacity"`
with used/limit plus the normal identity/version/timestamp fields. The initial migration
is preserved. Explicit module activation migrates/validates this private schema before
use, including upgrades from the original identity artifact and later restarts.
As always, developers alone must review/test/approve a migration candidate before applying
it. Generated SQL notices remain intact; tests are not a production migration guarantee.

Only six fixed framework rows may be initialized through this helper. Their stable,
non-secret technical IDs identify resource kinds, not users or authorization capabilities.
They are not generated per request or supplied by clients. Ordinary business aggregate
create/reconstitute semantics are unchanged. No credential/address/hash enters these models,
representations or counters.

| Resource | Initial limit |
| --- | ---: |
| Pending registrations | 10,000 |
| Actionable challenges | 20,000 |
| Unsent mail jobs, including retries/in-flight work | 10,000 |
| Protected unsent mail payload bytes | 67,108,864 (64 MiB) |
| Retained terminal metadata | 20,000 |
| Admission-control keys | 50,000 |

These are global operational storage resources for one identity installation/writer database,
not tenant allowances, subscription quotas, metered usage or time-window attempt counts.
The currently selected tenant is neither required nor changed.

## Atomic operations and transaction ownership

Internal callers explicitly initialize the complete positive-limit configuration within
the canonical writer transaction. Initialization creates missing fixed rows without
resetting existing usage. A mismatch with existing limits fails technically instead of
silently widening/replacing them. Controlled limit changes are explicit, persisted
operations; subsequent bootstrap configuration must match the intended stored settings.
No public configuration keys/management endpoints are committed by this slice.

reserve accepts a nonempty bundle of fixed resource kinds and positive long quantities.
It checks every requested resource before writing any counter. If any resource is full,
it returns false without a partial allocation. Successful changes use the generated
repository, preserving optimistic versions, ordinary invariants and change timestamps.
A lowered limit may be below existing usage: no live work is deleted or usage reset;
new allocations stop until there is room. Long arithmetic checks remaining capacity
before adding; maximum-range input cannot wrap into a negative allocation.

The helper requires an active synchronized Spring transaction already bound to its
DataSource. Writes reject read-only transactions, an unbound/incorrect source and mixed
JDBC writer boundaries. It constructs its private repository from that same template.
It does not open/commit a transaction, refund abuse attempts, switch tenants or retry
an entire business operation. Tests use two real Spring Boot application contexts,
independent pooled sources/transaction managers and one shared PostgreSQL database.

A fixed PostgreSQL transaction-scoped advisory lock serializes the short metadata
mutations across instances and releases with commit/rollback. This deliberately
conservative implementation avoids per-resource lock-order cycles; it is not a
throughput optimization or JVM-local mutex. Keep transactions short; never hold the
budget lock across remote calls, mail transmission or password hashing. Default
READ COMMITTED is the baseline; higher isolation/external conflicting writes can still
produce ordinary retryable transaction/optimistic conflicts, never a promised global
no-conflict execution. See [PostgreSQL advisory locking](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS).

withBudgetLock is an internal composition scope for later record lifecycle operations.
Acquire it before touching guarded identity/challenge/mail rows; reserve/release may
then participate reentrantly in that same transaction. This establishes a consistent
budget-before-record lock order. Its callback exceptions retain their original type;
it does not reinterpret a business rejection as unavailable storage. Diagnostic usage
reads are not authorization grants and must not become read-then-write admission checks.

## Accounting obligations that remain with record lifecycles

A storage counter alone cannot identify which job/challenge has already been completed.
The later lifecycle must win its guarded creation/deletion/terminal transition and
reserve/release the corresponding resources **in the same writer transaction**. A losing
retry must not decrement again. release checks the complete bundle before changing
anything and rejects underflow/inconsistent accounting, but cannot deduplicate record
identities it never receives. Elapsed time alone never frees a slot while its row/payload
still exists. Reconciliation must not silently reset counters to zero.

This slice proves counters share rollback with an identity write. It does not yet
integrate each registration/challenge/delivery row, prove per-record cleanup/idempotency,
apply the public known/unknown-address outcome contract or bound every actual resource.
Those are mandatory follow-ups before declaring the anti-DoS registration requirement
implemented. Independent security attempt budgets must remain spent after business
rollback; this transactional storage primitive must not be mistaken for those counters.

## Safe failure and evidence

Missing initialization, configuration drift and inconsistent release fail with bounded
technical reason codes. Storage failures do not look like a full budget or a successful
reservation. Raw JDBC causes/credentials are not retained by the helper. Normal optimistic
conflicts remain identifiable. There are no automatic per-request audit rows or logs.
The private repository/model must not be used to bypass the controlled budget protocol.

PostgreSQL proofs cover bundle denial without partial writes, rollback with an identity
change, restart, lowered limits, long overflow boundaries, two contexts competing for
the final slot, rollback unblocking a waiting writer, transaction/source/read-only checks,
tenant preservation, optimistic versions and permission failure/recovery. Artifact tests
reject imports of the private definitions. An upgrade test starts at the original module
migration with existing identity data and verifies preservation after the capacity migration.
CI/release gates require all proofs without skips.

Next are private credential/challenge records, real-row budget coupling, admission and
bounded cleanup, protected delivery, account provisioning and sessions/UI. No A01/A02,
full-SaaS or final manual acceptance gate is closed by this foundation alone.

[Keyed identity admission](saas-identity-admission.md) now reserves/releases actual
limiter rows under this budget. Both the coarse singleton and keyed configuration
remain charged; dynamic rows are bounded and never evicted while active to free slots.
The full public ingress/mail/pending-registration assembly remains incomplete.
