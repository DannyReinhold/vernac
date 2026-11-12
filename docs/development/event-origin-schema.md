---
title: Event origin schema and reviewed global upgrades
---
# Event origin schema foundation

Status: **implemented generated GLOBAL/tenant event storage and durable routing**.
The normal compiler now emits the origin-aware layout and matching storage bindings
together. Existing global publication, recorded events and deferred workers remain
supported after the reviewed migration. Tenant publication uses verified execution
selection and a restricted RLS runtime role; the queue captures tenantId and the worker
restores it before loading payload. This does not supply authentication/authorization.

See the [routing design](tenant-event-routing-contract.md) and the
[current runtime boundary](../contracts/events.md#internal-origin-and-legacy-storage-boundaries-060-development).

## Explicit, versioned metadata

Schema format 3 adds nullable `eventIsolation` metadata to a table. It identifies the
nominal event and whether this is its root. This is independent of `tenantIsolation`,
which describes aggregate graphs; one table cannot carry both classifications.
Formats 1 and 2 remain readable, with their existing JSON bytes and fingerprints.
Absent event metadata is omitted during compiler and module-runtime serialization.
Unknown schema versions and event metadata in older formats are rejected.

| Component | Relational representation |
| --- | --- |
| Publication tenant | Nullable `@tenantId` UUID; NULL means explicitly GLOBAL |
| Relationship scope | Non-null `@scopeKey` text: `GLOBAL` or `TENANT:<canonical UUID>` |
| Consistency | CHECK requires scope and tenant UUID to describe the same origin |
| Root identity | Existing global `@eventId` primary key, plus UNIQUE scope/event-id |
| Nested collection identity | Existing row IDs, list positions and primary keys retained; scope/row-id unique targets added |
| Parent relationships | Non-null scope included on both sides of every owned foreign key |
| Protection | ENABLE/FORCE RLS, global-or-current-tenant reads, exact-origin write check |

Retaining existing keys preserves their stronger uniqueness and avoids rewriting
identities during an upgrade. Scope-qualified foreign keys enforce origin even for
GLOBAL rows, where a nullable-tenant composite FK would skip validation. Nested
collection tables follow the same rule. Every classified child must connect to the
unique root of its event graph; cross-event/unclassified references are rejected.

The compiler's `EventSchema` helper constructs constraints without rewriting arbitrary
SQL text. Compiler and runtime schema validation agree on the resulting metadata.
This Java infrastructure is not additional Vernac source syntax. Generated adapters
select `JdbcEventStore.withOriginSchema` explicitly; old constructors remain legacy
GLOBAL storage, never an implicit request to reinterpret new tables.

## Read visibility is broader than delivery routing

The SQL policy permits a selected tenant to read global rows and its own rows, while
an unscoped reader sees global rows only. Inserts must match the actual transaction
origin exactly; a selected tenant cannot quietly write a GLOBAL row.

This does **not** replace exact-origin loading. Global loads match GLOBAL scope and
a NULL tenant on every payload table, including nested-owner subqueries. Tenant loads
match the claimed tenant origin and event identity. Row visibility alone is not
permission to deliver any visible event; runtime-role/policy verification and worker
restoration enforce the complete routing contract.

## Upgrading an existing global event graph

The planner supports adding origin metadata only to a corresponding existing
unscoped event graph. It retains root IDs, collection row IDs, values and list
positions. Historical rows remain GLOBAL; the tooling never guesses their tenant.

For **each existing payload table**, candidate generation requires an explicitly
reviewed SQL backfill of `'GLOBAL'` for its `@scopeKey`. A missing backfill or an attempt
to invent historical tenant assignments stops generation, including with `force`.
The usual transformation map uses keys such as:

```json
{
  "backfills": {
    "example.billing/@event:InvoiceIssued/@scopeKey": "'GLOBAL'",
    "example.billing/@event:InvoiceIssued.lines/@scopeKey": "'GLOBAL'"
  }
}
```

Save the reviewed expressions in a source-controlled transformation file and use
normal migration tooling, for example from the project directory:

```shell
mvn vernac:migration "-Dvernac.migrationDescription=event_origin_schema" "-Dvernac.transformations=src/main/vernac-schema/event-origin-upgrade.json"
```

Use a current 0.6.0 development compiler/runtime throughout the application and its
producer modules. For a fresh project, generated initial DDL already contains these
columns and no historical backfill is needed. For an existing project, generate and
review the next candidate; never rewrite an applied migration or old snapshot.

The candidate drops old referencing constraints before replacing them, adds nullable
columns, backfills scopes, sets NOT NULL, creates unique targets before foreign keys,
and finally installs RLS. It includes both the standard responsibility notice and
specific EVENT ORIGIN UPGRADE comments. The developer/operator alone must review,
test and approve provenance, ordering, existing data, runtime grants, deployment
compatibility and recovery. A generated or replay-checked candidate is no guarantee.

Removing origin classification or changing event ownership is not implemented and
cannot be bypassed with `force`. Aggregate classification transitions retain their
existing separate safeguards. Existing snapshots and applied migration SQL are never
rewritten by this upgrade.

## Module and activation boundary

Compiler/runtime metadata round-trip formats 1, 2 and 3 with matching fingerprints.
Global event modules now use normal dependency-first migration/validation/activation.
The temporary blanket rejection of format-3 event activation has been removed because
matching generated storage is available. Existing format-1/2 snapshots remain intact.

A storage operation verifies its actual table layout on the transaction's DataSource.
An old adapter against an upgraded table, or a new adapter against an old table, fails
with a safe capability-mismatch/rebuild diagnostic before payload SQL. The explicit
new layout requires both origin columns with their expected SQL shape and ENABLE/FORCE
RLS on every payload table. Global loads include exact-origin predicates even on
owner/superuser connections that bypass RLS. Tenant operations additionally enforce the restricted runtime role and exact mixed-origin
policy through the shared JDBC coordinator. Current adapters declare tenant delivery
SPI version 1; older adapters remain GLOBAL-only.

## Executable evidence and next step

Unit tests cover metadata round trips, format rejection, immutable legacy history,
explicit backfills, constraint ordering and refused classification changes. Real
PostgreSQL tests exercise generated DDL with a separate nonowner/NOBYPASSRLS role,
mixed GLOBAL/A/B visibility, nested foreign-key protection and exact-origin writes.
They compare a reviewed legacy upgrade with fresh DDL through the actual schema-check
catalog and verify that missing event RLS policies are detected. Module-runtime and
compiler fingerprints match for all three supported formats.

Generated-adapter tests also persist/read recursive values, duplicate/empty lists,
optional VOs, nanosecond timestamps and BigDecimal scale. They upgrade populated legacy
storage, reject old/new layout mismatches, preserve initial recipients, prevent global
loads of a tenant-tagged row even for a bypassing owner connection, and verify a GLOBAL
round trip with a restricted nonowner/NOBYPASSRLS role. Existing global worker retry,
restart, parallel claim and fencing tests continue to execute on the new layout.

The production worker now restores captured tenant routing and verifies origin again
before ACK. Real module/JAR tests exercise shared REQUIRED writes, independent retries,
restart, concurrent pool connections, forged routing, weakened policies and fencing.
A reviewed queue upgrade retains existing GLOBAL jobs and fails closed if missing.
See the [tenant event tutorial](../tutorials/tenant-events.md). Authentication, actor
policy, quotas and complete SaaS release acceptance remain separate; A19/A20/A21 stay OPEN.
