---
title: Tenant schema foundation — classification, new-table DDL and migration guards
---
# Tenant schema foundation — classification, new-table DDL and migration guards

Status: **schema foundation implemented and now selected by local tenant aggregates**.
See [local integration](tenant-persistence-integration.md) for current scope and limits.

This increment follows the [JDBC coordinator](tenant-jdbc-coordinator.md).
The shared model is now selected by local tenant declarations and their owned
storage plans; generated repositories consume that classification. No existing example becomes tenant-aware.

## Versioned classification without rewriting existing history

SchemaModel accepts model format 2 with explicit per-table tenantIsolation metadata:
the nominal aggregate identity and whether the table is its root. Model format 1
cannot contain tenant-classified tables. Scalar mapping version remains 1 because
scalar representations have not changed.

Absent classification retains unscoped meaning and is omitted from JSON, preserving
the existing format-1 JSON representation and fingerprints. History envelopes retain
their existing format; they can chain an unchanged legacy model to a format-2 model
that adds a new tenant graph. Existing snapshots and migrations are never rewritten.
Older tools cannot silently read tenant classification as an unscoped model.

## Shared technical vocabulary

TenantSchema constructs the technical UUID column @tenantId, root tenant/id unique
key, owned primary keys prefixed by @tenantId and tenant-qualified foreign keys.
Root id primary keys remain globally unique; list positions retain their existing
meaning. FK construction takes explicit source/target column lists and preserves
deferred relationships rather than rewriting an existing SQL clause.

Schema validation requires a matching root for each classified graph and rejects
foreign keys crossing tenant/unscoped or distinct aggregate-graph boundaries.
The lower-level factory takes explicit graph data; SchemaBuilder now supplies it
from local tenant declarations. It does not infer missing classification or rewrite
imported module contracts.

## New table candidates

MigrationPlanner emits the ordinary tables and constraints, followed by ENABLE ROW
LEVEL SECURITY, FORCE ROW LEVEL SECURITY and the owned @tenant policy on each new
tenant-classified table. Both USING and WITH CHECK compare @tenantId to the
transaction-local vernac.tenant_id setting; missing/empty context matches no rows.
The unchanged standard review/responsibility notice remains at the top of the SQL.

No roles, credentials or grants are invented. Deployment still requires separate
migration and restricted runtime identities and the explicit coordinator integration.

## Classification transitions fail closed

For an existing table, any tenant classification change is currently rejected
**before a candidate is returned**, including with force:

- Adding isolation reports TENANT ASSIGNMENT REQUIRED and the need for reviewed
  existing-data backfill.
- Removing isolation reports TENANT ISOLATION REMOVAL, names the affected table
  and explicitly explains that formerly separated data would be accessible through
  the unscoped repository.
- Changing the aggregate graph identity is also rejected.

This is intentionally stricter than the eventual
[reviewed conversion contract](tenant-technical-contract.md#tenant-classification-changes-in-mavenflyway-candidates).
Acknowledged conversion, complete historical assignment, collision handling and the
specific SQL warnings for such generated conversion candidates are still OPEN.
Force does not turn an unfinished conversion implementation into an unsafe candidate.

## Schema-check protection drift

The existing Maven schema-check catalog comparison now includes policy names,
commands, roles, permissiveness, USING and WITH CHECK, in addition to table RLS
flags. Missing, extra or changed policies on owned tables are differences.
Unrelated application-owned tables/policies remain ignored. This detects drift
against expected DDL; it is not a new automatic repair or a production database probe.

## Evidence and remaining work

Compiler tests cover JSON round trips and version rejection, unchanged legacy
fingerprints/history, tenant keys and FKs, new-table policies, incomplete/cross-boundary
graphs and both classification transitions with and without force.

Real PostgreSQL tests execute generated candidate SQL under a distinct non-owner role,
verify tenant row visibility and deferred qualified FKs, and prove policy/RLS drift
is visible to the Maven catalog while foreign application tables remain ignored.
Existing schema/migration Mojo tests still replay legacy candidates.

Original foundation verification (historical) executed 55 runtime, 488 compiler and 20 JDBC unit tests,
plus five selected Maven tests (including two new real PostgreSQL schema proofs)
and the 14 PostgreSQL isolation proofs. All passed without skips. The workflow
requires the new named schema proofs as well as the existing runtime/spike proofs.
No full IntelliJ or complete SaaS release verification is claimed.

The local tenant modifier, owned graph propagation and generated CRUD/query/count/
page integration now exist. Reviewed conversions, persistent tenant module activation and
event routing remain OPEN. A19/A20/A21 are not
VERIFIED. Next complete persistent tenant module activation and tenant event routing and reviewed
conversion paths while retaining unscoped source and history behavior.
