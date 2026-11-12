---
title: Tenant runtime foundation — implemented low-level APIs
---
# Tenant runtime foundation — implemented low-level APIs

Status: **runtime foundation implemented; local generated integration is now available**.
Current scope: [tenant persistence integration](tenant-persistence-integration.md).
This is the first additive production increment after the
[PostgreSQL/Spring feasibility spike](tenant-isolation-spike.md).
The technical [tenant contract](tenant-technical-contract.md) still describes later
schema, generated SQL, factories, entity graphs and module/event integration.

## Implemented in vernac-runtime

`org.vernac.runtime.tenancy` contains TenantId, TenantContext, TenantOwnership and
TenancyException. Existing generated code remains unscoped: `new PersistenceState(v)`
retains its version API and gains explicitly UNSCOPED ownership. No active tenant is
silently applied to existing constructors or repositories.

### Technical identity and safe errors

TenantId is a UUID-backed immutable technical identity, independent of users,
subscriptions and domain ID types. `value()` / `asString()` intentionally provide the
representation needed by trusted storage adapters. Default toString is redacted;
malformed-string exceptions do not copy their input or parser cause. This does not
make tenant IDs or data appropriate for normal logs.

TenancyException extends VernacTechnicalException, not VernacDomainException.
Its typed Code and message contain a safe stable classification, never actor,
tenant or business data. This increment does not add HTTP/MVC-specific code mapping:
existing safe technical-failure fallback behavior remains in place.

### Trusted selection, scopes and transaction pin

`TenantContext.openVerified(tenant)` is an infrastructure entrypoint **after** an
identity/membership resolver has verified the selection. It does not authenticate,
verify membership, establish a Spring Security principal or make a raw header trusted.
Do not bind request values to this method directly. The identity/SaaS adapter remains
future work; tests provide their own explicit verified entry.

Use try-with-resources. Scopes are thread-owned and must close in reverse order;
invalid order or a cross-thread close fails without corrupting the original scope.
Same-thread repeated close is harmless. Context does not propagate to another thread.
Without a transaction, properly nested selection can restore its previous selection.
Within an active Spring transaction, opening a verified selection also pins it;
closing/reopening cannot change it even before the first SQL operation. Changing a
selected or pinned tenant fails.

`currentTenant()` and `transactionTenant()` are read-only Optional metadata.
`requireTenant()` requires a live selection; a remaining transaction pin alone does
not grant a new call implicit tenant authority.

`bindToCurrentTransaction()` requires an actual synchronized Spring transaction and
live verified selection. It binds immutable tenant identity to a Spring resource;
repeat binding to the same tenant is idempotent. Synchronization suspend/resume and
completion preserve REQUIRED/REQUIRES_NEW lifecycle. Scope close does not prematurely
remove that pin: a different tenant cannot be reopened before transaction completion.

**The core pin is not JDBC connection binding.** The separate
[production JDBC coordinator](tenant-jdbc-coordinator.md) validates the actual
DataSource/physical transaction and initializes PostgreSQL transaction-local state.
The experiment now exercises that coordinator with real connections, including
the wrong-DataSource failure. Local tenant repositories now invoke it explicitly.
The core pin is not sufficient to secure a query or an unrelated transaction manager.

### Object origin and immutable assignment

TenantOwnership records NEW, RESTORED, DETACHED or UNSCOPED origin separately from the
current tenant/unassigned/unscoped boundary. NEW assignment is one-way and atomic;
concurrent different first owners cannot both succeed. This does not make a mutable
aggregate thread-safe or replace persistence authorization.

- `newAggregate()` captures the live verified selection if present, else waits for
  first assignment. Generated local tenant-marked root factories use this path.
- `newEntity()` remains unassigned until its root adopts it, even in an ambient scope.
- `restored(tenant)` is for a trusted storage adapter; it keeps that owner.
- `detached()` represents manual reconstruction and cannot be claimed as a NEW root.
- `unscoped()` retains legacy/global state and cannot silently become tenant-owned.
- `claimForTenant` and `claimUnscoped` validate or assign allowed NEW boundaries; they
  reject reassignment and detached/foreign ownership with a safe technical failure.

PersistenceState adds explicit technical factories `newTenantAggregate()`,
`restoredTenant(version, tenant)` and `detachedTenant(version)`, plus `ownership()`.
Restored tenant versions must be positive; existing constructor/version semantics
are unchanged. Version changes and transaction rollback do not clear ownership.
No public reattachment/import API or source-level factory parameter is introduced.

These APIs are architectural helpers for trusted generated/storage code. Application
Java can construct technical objects, so they are not unforgeable security capabilities.
The local tenant generator now selects these factories and adds technical entity
ownership, as described in the integration record. Module/event work remains separate.

## Validation and limits

Original foundation verification (historical): `mvn -pl vernac-runtime -am clean install` executed **55 tests**, including
16 new foundation tests. The standalone PostgreSQL project then executed **12 tests**
using the actual installed runtime. Both report zero failures/errors/skips. The
workflow now builds the runtime first and checks all named foundation/spike proofs.
No IntelliJ/full reactor build or complete SaaS implementation is claimed.

Tests cover LIFO/thread ownership, pin completion, rollback, close-before-commit,
scoped/global legacy behavior, concurrent assignment, detached/restored provenance,
safe diagnostics, actual physical-connection suspension/restoration and scoped SQL.
No credentials, SQL values or private tenant data are added to production logging.

## Still OPEN

Whole-SaaS completion and the remaining identity/whole-SaaS integration; module metadata/compatibility/migrations;
identity/membership integration and immutable actor context; HTTP/MVC behavior;
subscriptions/limits and SaaS UI/Wizard. A19/A20/A21 are not VERIFIED by this PR.
Existing unscoped repositories do not start filtering merely because TenantContext exists;
local tenant-marked repositories now explicitly invoke the coordinator.

Local generated persistence is described in the integration record. Tenant event routing is implemented; next complete
identity integration and reviewed conversions. Existing
simple non-SaaS applications and the separate SaaS starter objective remain distinct.
