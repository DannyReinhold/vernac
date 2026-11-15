---
title: Explicit operation limits, capacity and quota accounting
---
# Explicit operation limits, capacity and quota accounting

Status: implementation in progress, D04/D06 baseline. A05–A07 remain OPEN until
concurrency/restart/recovery, generated consuming application and full integration
acceptance are evidenced. No new Vernac syntax is introduced in this slice.

The developer uses ordinary Vernac domain values and an explicitly assembled
RestrictionLedger. Its mandatory AllowanceFacts supplies current typed allowances
from subscription snapshots or a named application policy. Missing facts/storage are
technical failure, never unlimited access. Permissions and verified account/tenant
selection remain separate prerequisites. These APIs do not authenticate an AccountId.

```vernac-application
application consumer.restrictions {
    tenancy explicit;
    authorization explicit;
    activate vernac.saas.subscriptions;
    activate vernac.saas.restrictions;
}
```

## Three distinct meanings

- requireOperation checks actual batch/request size without accounting or side effects.
- capacity runs local product writes under an account lock and reads occupancy through
  a named repository fact before and after. All participating create/delete/deactivate
  paths must use this boundary; this slice does not automatically infer count semantics
  or wrap arbitrary handwritten SQL. reduceCapacity permits deletion after downgrade
  but rejects increasing occupancy. There is no duplicated persisted capacity counter.
- local quota consumption shares the physical transaction with the callback. Nested
  calls charge separately when reached; choose one accounting owner to avoid deliberate
  double charging. Return/void helpers invoke the callback once, with no hidden retry.
  Rollback also rolls back quota. Callbacks here must contain only local transaction work.

The account lock is shared across participating limits, capacities and quotas to give
one clear lock order. One physical transaction is pinned to one account; nested attempts
to switch account fail. The lock is a PostgreSQL transaction advisory lock, not a JVM
counter. Facts execute inside the actual product transaction. A hash collision only
adds serialization. Supported operation paths must use the same configured datasource
and fact definition; the API is not a sandbox around arbitrary trusted Java/SQL code.

## External reservations and uncertain outcomes

reserve commits independently before external work and rejects an inherited transaction.
The operation key is account-wide across benefits and periods: moving a retry into a new
period cannot charge again. A duplicate must retain exact benefit/window/amount or fails
OPERATION_CONFLICT. Only the creator receives created=true. A separate durable beginDispatch transition
changes RESERVED to IN_DOUBT and grants exactly one dispatch attempt. It requires the
original window still to be current. external invokes its callback only for its creator
after this transition, outside the writer. A crash, exception, or failed confirmation
leaves a durable IN_DOUBT amount. A crash before dispatch leaves RESERVED, for which an
explicit recovery may obtain the one dispatch authorization while its window is current. Retry does not execute it again.

confirm is idempotent and moves the original reservation to consumed in its original
period, even after rollover or downgrade. releaseKnownUnused is idempotent only for an
authoritatively unused operation. Expiry/timeout is not evidence that the remote effect
did not occur, so there is no automatic release. CONFIRMED and RELEASED cannot reverse.
ExternalResult carries a safe state and reservation ID; it never stores arbitrary
callback data or provider exceptions. Duplicate results do not recreate a previous T.
Reconciliation uses the authoritative external operation/ledger, not response replay.

Zero is finite zero; -1 explicitly means unlimited, still subject to integer overflow
protection. Usage windows are half-open and explicit. Local consumption and new
reservations require the current window; resolution always targets the original one.
No implicit UTC/month boundaries are guessed from a benefit name. resizeBeforeDispatch changes a RESERVED amount absolutely and atomically, charging or
refunding only the difference. It is forbidden after dispatch or outside the original
window; a dispatched uncertain effect must never be reduced speculatively. Retrying
reserve after adjustment must use its current amount. pending provides an account-scoped
UUID cursor with at most 100 records for an authorized operator to reconcile explicitly;
there is no public endpoint or implicit release of IN_DOUBT work. Full integrated
provider/operator acceptance remains required in this same topic.

## Developer and tooling boundaries

Models, repositories, migrations and module activation follow existing Vernac contracts.
There is no provider-specific keyword or automatic self.quota syntax in this implementation.
Compiler/LSP/IDE syntax and the broad language/AI references are unchanged for that reason.
The complete SaaS sample must demonstrate the supported Java-to-Vernac integration,
local/provider failure paths and tenant-safe generated product persistence before A05–A07
are closed. The later release audit must reject missing/skipped required proofs.
