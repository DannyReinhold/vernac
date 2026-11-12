---
title: Event origin schema and reviewed global upgrades
---
# Event origin schema foundation

Status: **implemented schema infrastructure, not enabled generated event storage**.
The normal compiler still emits the existing event layout. Durable tenant emission
remains blocked; neither an `EventIsolation` object nor schema format 3 authorizes
publication or starts a tenant worker. This step establishes the relational contract
before switching generated adapters and their tables together.

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
This Java infrastructure is not additional Vernac source syntax and should not be
used as a substitute for the pending generated storage implementation.

## Read visibility is broader than delivery routing

The SQL policy permits a selected tenant to read global rows and its own rows, while
an unscoped reader sees global rows only. Inserts must match the actual transaction
origin exactly; a selected tenant cannot quietly write a GLOBAL row.

This does **not** replace exact-origin loading. The future event store must additionally
match a delivery's claimed origin and event identity. Row visibility alone is not
permission to deliver any visible event. Runtime-role/policy verification and worker
origin restoration are still required by the complete routing contract.

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

This illustrates the infrastructure contract; normal source compilation does not yet
request this upgrade. Do not add these transformations to an existing application
until the generated-storage change actually selects format 3 for its event graph.

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

Compiler/runtime metadata can round-trip formats 1, 2 and 3 with matching fingerprints.
Until generated storage and worker routing support the new layout, the module
coordinator rejects a classified event schema **before executing any module or
application migration**. Reading a data format is not evidence that the associated
runtime capability is operational. Ordinary existing applications/modules remain on
their current schema and activation path.

## Executable evidence and next step

Unit tests cover metadata round trips, format rejection, immutable legacy history,
explicit backfills, constraint ordering and refused classification changes. Real
PostgreSQL tests exercise generated DDL with a separate nonowner/NOBYPASSRLS role,
mixed GLOBAL/A/B visibility, nested foreign-key protection and exact-origin writes.
They compare a reviewed legacy upgrade with fresh DDL through the actual schema-check
catalog and verify that missing event RLS policies are detected. Module-runtime and
compiler fingerprints match for all three supported formats.

These are schema proofs. They do not prove generated event bindings, production
worker origin restoration, JAR-based event capability negotiation or tenant outbox
acceptance. Next: select this layout together with compatible generated storage,
then integrate worker routing and exercise fresh/upgrade/module/concurrency paths
before lifting the emission guard. A19/A20/A21 remain OPEN.
