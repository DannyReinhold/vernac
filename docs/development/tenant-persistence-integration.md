---
title: Local tenant aggregate persistence — implemented scope and limits
---
# Local tenant aggregate persistence

Status: **local aggregate/owned graph persistence implemented; complete SaaS integration remains OPEN**.
This supersedes the earlier foundation-only limits in the runtime, JDBC and schema notes.

## Source and generated APIs

tenant aggregate selects tenant-bound persistence; global aggregate and an ordinary
aggregate without an explicit-classification rule remain unscoped.
tenancy explicit accepts tenant/global and rejects an unclassified own persisted root.

Public create/reconstitute business arguments, ID generation, equality and timestamp
semantics remain unchanged. Tenant creation captures verified selection when present,
otherwise first save assigns ownership once. Public manual tenant reconstitution is
detached and cannot be saved as if newly created. JDBC uses reserved technical restore
helpers with ownership from the checked stored discriminator. Resetting a restored
version to zero does not make the object NEW.

Generated entities retain technical ownership outside Read/Write/Access views and
toString/equality. New entities wait for root adoption; JDBC restoration preserves the
tenant or unscoped boundary. Manual entity reconstruction stays unscoped for existing
global use and cannot be imported into tenant storage. Current module-contracts-10
entity imports preserve ownership through their technical restoration factories.
Older dependency metadata must be rebuilt; it cannot silently acquire that capability.

## Complete local owned graph

SchemaBuilder now selects format-2 classification for tenant roots and their entity,
value-collection and relationship tables. Root IDs retain global PK uniqueness; owned
keys and FKs include @tenantId. Value rows also carry @aggregateId, with appropriate
owner checks/qualified unique keys, so nested VO collections can be batched per root.
List positions and shared entity instance restoration retain their existing behavior.

JdbcAggregateStore uses one tenant conjunct around the complete user predicate.
It applies before search cardinality, offsets/counts and cursor windows, and on owned
batch loads, version rechecks, updates, deletion and orphan cleanup. Inserts assign
the checked discriminator; updates never change it. Cursor authentication includes
the tenant parameter and schema classification. All graph ownership is checked before
the first graph write. Rollback does not clear in-memory provenance or reset versions.

PostgreSQL row checks remain additional protection. Generated stores require the
exact owned @tenant USING/WITH CHECK policy in the supported PostgreSQL 17 form,
including ALL/PUBLIC/permissive policy shape and absence of extra policies, as well
as ENABLE/FORCE flags and restricted runtime roles. Owner-role membership is rejected,
including roles which could become owner through SET ROLE. Schema-check remains the
broader structural drift check.

Tenant SQL/data-access failures do not retain driver causes containing DETAIL,
parameters or tenant values. Duplicate keys/optimistic failures become safe generic
persistence conflicts; other database failures use TENANT_STORAGE_FAILURE.
Existing unscoped error behavior is unchanged.

## Transaction and initialization contract

Generated tenant repository beans depend on database initialization. Initialize them
eagerly after migrations, outside request transactions; lazy first construction inside
an active transaction is rejected. The supported synchronous Spring JDBC DataSource
must have autocommit enabled outside Spring transactions and an empty/unset tenant
setting. Constructor checks reject incompatible baselines rather than treating an
unrelated synchronized connection as the intended transaction.

Coordinators for different repositories share a transaction resource by DataSource
identity. Each table set/policy mode is verified before its first use in that physical
transaction. REQUIRES_NEW suspends/restores the resource; completion removes it.
Pool reuse, changed settings and wrong-DataSource access retain fail-closed checks.
No session-global setting, automatic default tenant or XA/reactive support is added.

## Migrations and privileges

Fresh tenant schemas/Flyway candidates include the complete graph and policies.
The Maven schema/migration goals use the same model. Existing tenant-to-global and
global-to-tenant conversions remain blocked even with force; no data-assignment or
boundary-removal strategy is invented. In explicit mode, accidentally deleting the
tenant modifier fails compilation before reaching that diff.

Ordinary additive migrations within an already tenant-classified graph still use
the existing reviewed backfill/conversion mechanism. Migration credentials must
intentionally authorize access to all affected tenant rows (for example, a separate
migration role with BYPASSRLS). FORCE RLS applies to ordinary non-bypassing owners too;
such a role cannot perform a cross-tenant backfill merely because it owns a table.
The runtime role must never inherit those privileges. No role creation/grants or
credential material is silently inserted into migration candidates. Review ordering,
data and deployment compatibility as always; a generated candidate is not a guarantee.

## Explicit remaining boundaries

- Tenant type exports and imported entity graphs use module-contracts-10 metadata.
  Tenant repository publication remains rejected by compilation and direct publication:
  activation must first support separate migration/runtime DataSources. Global module
  persistence remains unchanged; no tenant context is established by imports.
- Synchronous immediate events retain the current context/transaction. Recorded/deferred
  emission while a tenant is selected fails with TENANT_EVENT_DELIVERY_UNSUPPORTED
  before storage or dispatch, and the failed event context prevents commit. Durable
  envelopes, worker routing and module delivery are still OPEN.
- Authentication, membership validation, immutable actor context, permissions, quotas,
  subscriptions and provisioning are not supplied by TenantContext.openVerified.
  Only trusted infrastructure may establish selection.
- No finished SaaS example/Wizard is claimed. Existing simple starters remain unchanged.
  A19/A20/A21 and the full release acceptance ledger remain OPEN.

## Verification scope

Compiler tests cover owned graph classification, technical APIs, source modifier
removal diagnostics, module scope compatibility and rejection of persistent tenant modules. Real PostgreSQL tests
compile generated Java and exercise complete graphs, nested value collections, shared
children, multiple repositories in one transaction, global coexistence, predicates,
singletons, counts, tenant-bound cursors, CRUD/cleanup, detached/reset states, rollback,
cross-boundary entity reuse, parallel tenants, same-tenant optimistic conflicts,
role/policy rejection and safe collision errors. Existing unscoped PostgreSQL/module
suites remain required regression evidence. See the PR/CI reports for executed counts.

For use and operational prerequisites, see the [tenant persistence tutorial](../tutorials/tenant-persistence.md).

## Internal responsibilities

`SchemaBuilder.collection` resolves the collection and dispatches to entity-link or
value-row planning. Presence markers, value-table keys, owner foreign keys and
load predicates have separate private helpers. `field` similarly dispatches enum
codes and flattened value objects instead of mixing both representations. These
helpers preserve the existing schema, constraint ordering and storage-plan output.

In `JdbcAggregateStore`, batched relation reads and root-version verification remain
in that order, within the same chunk. Saving still locks/writes the root first,
deletes obsolete relationships in reverse dependency order, then writes desired
relationships in dependency order. The private methods make those phases explicit;
they do not introduce additional queries or transaction boundaries.

`JdbcTenantCoordinator` separates baseline connection checks, transaction binding,
existing-binding verification and per-table role verification. SQL failures still
pass through the same sanitization boundary and connections are released in the
same `finally` blocks. This decomposition is internal and changes no language or
public runtime contract.
