# Event delivery: agreed design and implementation stages

## Status

Implemented: domain events, recording, synchronous and deferred listeners, relational
storage and schema migrations, per-listener jobs, automatic Boot polling, bounded retry,
SKIP LOCKED claims, token-fenced acknowledgement and lease recovery. Defaults and
operational limits are defined in the [event contract](../contracts/events.md).

Still deferred: management UI/API, manual retry/replay tooling, retention, heartbeat,
strict ordering and automated event evolution/upcasting. Physical schema migrations do
not prove that historical payloads pass changed constructors or validation rules.

## Domain facts and subscribers

An event describes what happened. The publisher reports it with emit. Each listener
chooses synchronous participation or deferred execution. A direct domain-service call
remains appropriate when an operation explicitly depends on another operation; events
are not a mandatory substitute for straightforward orchestration.

Synchronous listeners run immediately in the existing transaction. Deferred listeners
are inbound adapters invoked by a transactional delivery runtime. A deferred
listener does not itself introduce an extra nested transaction. The runtime owns a
processing transaction so that application DB effects and acknowledgement are atomic;
a called REQUIRED usecase joins it. This differs from an ordinary HTTP controller,
which does not need an atomic persistent delivery acknowledgement.

## Relational storage, not generic serialization

Persist the event if at least one deferred listener currently subscribes, or if the
model explicitly declares `recorded event`. Otherwise emit has no persistence effect.
Recording is opt-in domain history, independent of subscriber selection. It does not
claim event sourcing or legally compliant auditing.

Use one table per event type, with a namespace-qualified technical name such as
`"example.delivery"."@event:StopMoved"`. Reuse the reviewed value mapping: flattened
VOs, enum values, ordered lists/sets, optional presence and lossless scalar components.
Store EventId as UUID and occurredAt with its nanosecond remainder. Domain classes
need not implement Serializable or carry Jackson annotations. No generic JSON payload
is required in this design.

A common delivery table holds one job per (event type, EventId, listener identity).
The event payload is stored once. Resolve and persist the subscriber set when emit
runs, in the publisher transaction. A subsequently added listener does not automatically
receive historical events. An explicit replay feature would require a separate contract.
Persisting again with the same event identity must not silently replace different data.

The stable default listener identity is its fully qualified Vernac name, not its
Java implementation class. The event type identity is likewise its fully qualified
Vernac name. Renames are deployment/data changes: drain outstanding jobs or explicitly
migrate their identifiers. Missing listeners leave visible blocked jobs and do not
consume retry attempts. Do not silently discard or reroute them.

## Parallel workers and crash recovery

Two transactions are required:

1. A short claim transaction selects due jobs with row locking and SKIP LOCKED,
   writes a fresh claim token, lease expiry and started-attempt count, then commits.
   The claim is now visible to other workers. It does not hold a database transaction
   open while waiting to start application work.
2. The worker invokes the listener in a new processing transaction and EventContext.
   Application database changes and successful job acknowledgement commit together.
   Acknowledgement is conditional on the current claim token. If ownership was lost,
   fail and roll back all processing-transaction database changes.

Failure rolls back the processing transaction. A separate short transaction records
failure and schedules a retry, also conditional on the claim token. A crash between
claim and processing, or before recording a failure, leaves a recoverable expired lease.
Lease recovery issues a new token; stale workers cannot acknowledge or overwrite the
new claim. Long-running work needs an appropriate lease/heartbeat policy.

The status "in progress" alone is insufficient. Claim visibility, atomic selection,
lease recovery and fencing must be implemented and tested together. External effects
can still happen more than once. Delivery is at-least-once; remote integrations need
idempotency using event/listener identity. A database rollback cannot undo an email,
HTTP request or message already accepted by another system.

## Selection, retries and termination

Prefer the oldest *due* job by job creation time with a stable job-ID tie-breaker.
Occurrence time is domain data and can be historical; do not use it as the scheduling
clock. A failed older job waiting for its retry does not block later jobs. Parallel
workers do not promise completion order. Strict per-aggregate ordering is a separate
future capability, not an accidental guarantee.

Count started attempts including the first. Configure a maximum, a bounded backoff,
and optionally maximum age. Exhausted jobs become visibly terminal; retain last error,
last attempt, next attempt, and completion/failure timestamps as applicable. Manual
retry must be explicit and observable. Failure for listener A does not prevent listener
B's own delivery job from succeeding.

Column names and retry defaults are implemented; an administrative API remains future
work. No automatic event/job cleanup is introduced initially. Recording and operational
retention need separate policies before any cleanup is enabled.

## Evolution and migration responsibility

Pending payloads depend on their entire value graph, not only the event declaration.
Changing a referenced VO, enum, collection, scalar mapping or validation can make
historical events impossible to reconstruct. Reconstitution validates current rules.
Creating a new event type is helpful only if its historical dependencies stay compatible.

Developers choose between compatible evolution, explicit data migration, retained old
models/handlers and draining before deployment. Do not force data rewrites when domain
or legal requirements require historical representations to remain unchanged.

Extend schema snapshots/migration tooling to cover owned event and delivery tables.
Generated SQL is a migration candidate. The application developer alone is responsible
for review, testing and approval before any application to a database, including data,
ordering, compatibility, locking, execution time, backups and recovery. Force only
acknowledges an identified risk; it neither guarantees correctness nor transfers that
responsibility. Repeat this notice in generated event migrations too.

A dedicated event-evolution tutorial must include a change to a shared value type, an
outstanding delivery, and a listener rename. Empty-database replay cannot prove that
historical events remain processable.

## Implementation sequence and acceptance evidence

1. Implemented: event classes and synchronous execution, including generated Java tests.
2. Implemented: event storage and owned-schema integration; emission and per-listener job insertion
   commit/rollback together with the publisher. Add recorded-only and multi-listener tests.
3. Implemented: claim/processing/failure transactions, token fencing and leases. PostgreSQL coverage:
   concurrent workers, crash after claim, expired lease, stale acknowledgement, processing
   rollback, duplicate external delivery and independent listener outcomes.
4. Next: administration tooling and deliberate retention/replay policies. FAILED and
   BLOCKED jobs are already visible through SQL; defaults provide bounded retries.
5. Migration/evolution tutorial and explicit historical-payload tests.

Revisit service operation read/modify classification and shallow read views separately.
Usecase/service dependency delivery is now unified through self Access; no broad
refactoring of domain-state rollback or recursively read-only graphs is part of events.
