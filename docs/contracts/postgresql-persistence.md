# PostgreSQL persistence mapping contract

## Status and scope

Mapping version 1 defines names, storage layouts, recursive value flattening and
migration candidates. The schema tooling reads explicit repository declarations.
Aggregates without repositories do not own tables. Direct/optional entity fields,
entity lists/sets and recursively contained entities are supported by schema generation.
Shared references within an aggregate use one entity state table per resolved type.

The reviewed multi-file pipeline now generates JDBC repositories with `byId`, `save`
and `delete`. `SchemaBuilder` captures `StoragePlan` bindings while building the DDL;
the JDBC generator uses these exact columns, presence markers and relation targets.
Both compiler entry points use the reviewed repository generator; the old generator
class is not used by these paths.

Runtime scalar codecs preserve the declared component layouts. The standalone
[persistence demo](../../examples/persistence-demo/README.md) exercises Spring JDBC,
Flyway and complete aggregate graphs. Custom queries remain a later feature.

## Responsibility for migrations

Every generated migration is a **candidate**, not an approved deployment script.
The application developer is solely responsible for reviewing, testing and approving
it before application to any database. Review operation ordering, existing data,
application compatibility, locking, duration, backups and recovery procedures.
Generation and automated verification provide no correctness, safety or deployment
guarantee. This notice is also included in generated SQL and command output.

Force only allows candidate generation to continue despite identified data-loss
risks. It does not execute SQL, approve a migration, guarantee success, invent a
backfill or infer a conversion. Some destructive operations may fail rather than
lose data, e.g. an enum constraint excluding values still present in rows.

## Names

- PostgreSQL schema: the exact full Vernac namespace, including dots and case.
- Root table: the exact aggregate name.
- Column: the field path after single-value unwrapping, with dots between multi-value fields.
- Every schema, table, column and constraint identifier is quoted. Embedded quotes
  are escaped by doubling, never by treating the name as SQL.
- No snake-case conversion, transliteration, Unicode normalization or case folding.
- Technical columns use `@` names; user identifiers cannot start with this character.
- Root identity uses `id`. Metadata uses `@version`, `@createdAt`, `@updatedAt`.
- At most 63 UTF-8 bytes: preserve the name. Otherwise use the longest whole-codepoint
  prefix fitting 30 bytes, `~`, and the first 32 lowercase hex digits of SHA-256 of
  the original UTF-8 name. The rule is independent of declaration order.
- Check physical-name collisions, including index names. Never rely on PostgreSQL
  truncation. A detected hash collision fails generation rather than merging objects.

Example: `"org.example.delivery"."Tour"` and `"address.street"`.
The latter is one column identifier. A logical name and its physical name are related
by the versioned naming rule, not a lookup in the current developer's database.

## Scalar layouts

Primitive and boxed variants share a representation. Optionality controls nullability,
not the PostgreSQL type. There is no unknown-type-to-TEXT fallback.

| Type | Storage |
| --- | --- |
| boolean | BOOLEAN |
| byte | SMALLINT, -128..127 |
| short | SMALLINT |
| int | INTEGER |
| long | BIGINT |
| float | REAL |
| double | DOUBLE PRECISION |
| char | INTEGER, UTF-16 code unit 0..65535 |
| String | TEXT; no arbitrary length limit |
| UUID / Vernac id | UUID |
| BigInteger | NUMERIC with integral, finite value check |
| BigDecimal | finite NUMERIC plus INTEGER scale |
| Currency | TEXT, ISO code |
| Year | INTEGER |
| Month | SMALLINT, 1..12 |
| DayOfWeek | SMALLINT, ISO 1..7 |
| ZoneId | TEXT, Java zone identifier |
| ZoneOffset | INTEGER, offset seconds -64800..64800 |
| LocalDate | DATE |
| LocalTime | TIME(6) plus nanosecond remainder |
| LocalDateTime | TIMESTAMP(6) plus nanosecond remainder |
| Instant | TIMESTAMPTZ(6) plus nanosecond remainder |
| OffsetDateTime | Instant layout plus offset seconds |
| ZonedDateTime | LocalDateTime layout plus offset seconds and zone identifier |
| OffsetTime | LocalTime layout plus offset seconds |
| Duration | BIGINT seconds plus INTEGER nanosecond fraction |
| Period | INTEGER years, months, days |
| YearMonth | INTEGER year, SMALLINT month |
| MonthDay | SMALLINT month and day |

Additional scalar components use `@component:path`, e.g. `@scale:price.amount`,
`@nanoRemainder:departure`. Period/YearMonth/MonthDay have only named components.
Duration's base column stores seconds. Nano remainders are 0..999; duration nano
fractions are 0..999999999. All parts of an optional scalar are null or all are present.

The codecs preserve BigDecimal scale (including negative scale), trim
native timestamps to microseconds explicitly and retain the remaining nanoseconds,
and reconstruct zone-bearing values without silently changing offset/zone semantics.
PostgreSQL representable ranges and valid text encoding remain technical boundaries;
unsupported values must fail rather than be rounded or truncated silently. Changes
in Java time-zone rules can require explicit handling when restoring ZonedDateTime.
Database checks are structural checks, not a replacement for domain reconstitution.

## Enums

Enums store TEXT codes through an explicit mapping, never ordinals or implicit
runtime `name()` calls. Initial codes equal constant names. Subsequent generation
preserves codes from the previous model. A complete explicit mapping override can
preserve a code during a deliberate constant rename. Duplicate codes are rejected.
The mapping lives in schema metadata, never as `dbValue()` on the domain enum.

## Flattening and optional presence

Single-value wrappers reuse the containing field path. Multi-value wrappers append
their field names and recurse. IDs and enums are leaves. This is based on resolved
type identities across files/namespaces, not on simple-name lookup or reflection.

An optional value needs no marker when a recursively required scalar leaf witnesses
its presence. A required field whose type itself has only optional leaves is not
such a witness. Optional all-null-capable wrappers receive BOOLEAN presence columns.

A marker uses `@present:<storage-path>:<qualified-VO-identity>`. Including the VO
identity distinguishes wrapper identities sharing one flattened path. An existing child
presence marker can itself witness the containing VO, avoiding a redundant marker. A marker is true/false when its owner exists and null when an outer owner is
absent. No marker is added merely because a required VO has optional leaves.

Checks enforce absence shapes and required scalar components. Reconstruction must
also validate domain invariants and reject malformed representations. Optional
collection fields have markers to distinguish absent from present-but-empty.

## Value collections

One table per collection containment path: `Tour.tags`, recursively for nested
collections. Root collections reference `id`; nested collection rows reference their
owner row's generated `@rowId` UUID. Rows have `@ownerId` and `@rowId`.
Lists additionally have zero-based `@position` with primary key `(ownerId, position)`;
row IDs are unique. Sets have no position and use the technical row ID as primary key.
Their Java equality semantics are enforced by the collection factories and loader; a generic
SQL UNIQUE across arbitrary flattened values would not implement Java equality.

No ordering is inferred for sets. List loading must explicitly order by position.
The writer replaces value-collection rows as a unit. Entity states and relationships
are synchronized separately by their generated row keys.

## Entities and shared containment

Entity state and relationships have separate representations. The aggregate root
owns the complete graph. Multiple paths may refer to the same entity; there is no
single-immediate-parent restriction. An entity's persistence key consists of the
root table, aggregate ID, fully qualified entity type and entity ID.

- State table: `<Root>.@entity:<fully-qualified-entity-type>` in the **root namespace's**
  SQL schema. One table per root/type, irrespective of the number of reference paths.
- State primary key: `(@aggregateId, id)`. `@aggregateId` references the root `id`.
- Entity fields use the same VO flattening, scalar layouts and presence rules as roots.
- No separate entity optimistic-lock version or technical timestamps are added.
  The root version protects the entire graph.
- A direct entity field stores its entity UUID under the field name. Optional fields
  are nullable. The composite FK uses root `id` (or owner `@aggregateId`) plus that
  field, targeting `(@aggregateId, id)` of the entity state table.
- A collection relationship table is named `<owner-state-table>.<field-name>`.
  Thus relationships of a shared entity are stored once, not once per incoming path.
- Root relationships have `@aggregateId`; entity-owned relationships additionally
  have `@ownerId`. Both owner and target are protected by aggregate-scoped FKs.
- List rows add `@position` and `@entityId`; PK is owner columns plus position.
  Repeated entity references are allowed, including multiple positions in one list.
- Set rows have `@entityId` without position; PK is owner columns plus entity ID.
  Repeated references in the same set cannot create duplicate rows.
- Optional entity collections use the existing collection presence marker to
  distinguish absence from an empty collection.
- Value collections contained in entities reference their owner using
  `(@aggregateId, @ownerId)`; their list key is `(@aggregateId, @ownerId, @position)`. Deeper value-collection rows reference their owning
  value row's unique `@rowId`, as before.

Foreign keys targeting entity state are `DEFERRABLE INITIALLY DEFERRED`. This allows
inserting a root with a required entity reference before inserting that entity,
whose aggregate-owner FK requires the root to exist. Final referential integrity is
checked at transaction end; the writer must not interpret a successful statement as
successful commit. Owner FKs stay immediate. No constraint is disabled.

Foreign keys do not cascade deletes. Shared references must be considered before
removing state. Tables belonging to unrelated application code are outside the
mapping contract; their FKs can still cause PostgreSQL to reject a delete. Vernac
must propagate that failure, not disable constraints or use CASCADE.

### Runtime behavior

Loading must use one identity map per loaded aggregate, keyed by entity type and ID.
Every occurrence returns the **same Java instance**. Reconstitution still validates
invariants. Lists use explicit ordering; sets promise none. The identity map is not
a global cache or a cache shared between independently loaded aggregates.

Before writing, traverse every direct field and collection from the root. Multiple
references to one Java object are valid. Different Java objects with the same entity
type and ID in that graph must fail explicitly; never choose a winning snapshot.
Cycles in containment remain forbidden. Sharing a node across branches is not a
cycle; traversal must distinguish active ancestors from already visited nodes.

Save compares stored state/relationships with the final reachable graph. Insert new
entities, update existing states, and synchronize relationships separately. Removing
one relationship never deletes an entity that remains reachable through another.
An entity, including an otherwise detached subgraph, is removed only when unreachable
from the root. Remove obsolete relationships before unreachable states, and respect
foreign-key dependency order. Reordering lists must avoid transient position-key
collisions; delete/reinsert-all is not the entity synchronization strategy.

The root optimistic update must succeed before child writes. All writes occur in
one surrounding usecase transaction. Failed writes do not restore in-memory state.

## Structural constraints and migrations

Constraints enforce a reconstructable representation, not arbitrary `validates`
expressions from the domain. Required top-level columns rely on NOT NULL. Optional
multi-column scalars enforce all-present/all-absent. Optional VOs retain absence
checks so values cannot survive beneath an absent wrapper. Marker presence checks
are omitted when NOT NULL already covers them. Enums retain explicit allowed-code
checks; expanding a code set requires a schema migration before new codes are written.

Migration planning compares constraints individually. Unchanged constraints stay in
place unless a referenced column's storage type/codec changes or disappears. Changed
constraints are dropped before column operations and restored afterwards; incoming
foreign keys are considered too. Foreign keys are dropped before referenced UNIQUE
constraints and added after other constraints. Explicit review remains mandatory,
particularly for data transformation, enum removal and deployment compatibility.

## Repository contract

The agreed domain interface belongs to `<namespace>.domain`; JDBC implementation
belongs to `<namespace>.adapter.outbound.jdbc`. Shared execution code lives in
`vernac-runtime-jdbc`; generated bindings remain in the adapter.

- `byId(id)` returns a complete aggregate or AggregateNotFoundException.
- `save(aggregate)` inserts new roots or updates existing roots with optimistic locking.
- All operations require a surrounding usecase transaction (MANDATORY).
- No lazy loading. Errors do not restore object state automatically.

Planned query extension, not part of this runtime increment:

- Explicitly requested domain collection types will represent search results.
- Custom infrastructure code will supply conditions or IDs to the shared aggregate
  loader. No arbitrary SELECT projection language is planned.
- Proposed `loadAll(ids)` preserves first-occurrence input order, deduplicates IDs,
  returns empty for empty input and fails if a requested ID is missing.

The `query ... where ... order by ...` syntax discussed during design is not yet
part of the grammar. Existing repository declarations suffice to select schema roots.

## Runtime boundaries

A fresh aggregate has version 0. The first insert persists version 1; each save advances
it under an optimistic root-row lock. A loaded/deleted root is never upserted through
an INSERT fallback. State changes after rollback are not repaired in Java.

Loading issues one query per mapped table, not one query per entity. A final root-version
check detects commits overlapping the read and fails rather than returning a mixed
snapshot. The transaction/connection isolation must be provided by the same Spring
DataSource transaction manager used by the repository.

The writer collects and checks the complete reachable graph before writing. Relationship
list rows retain position keys; reordering updates their target IDs without key swaps.
Unreachable states are removed after obsolete dependent rows. This is a per-aggregate
algorithm, not a general-purpose ORM or global identity cache.

Domain events pulled after saving are delegated to the configured EventDispatcher in
the same transaction. The new schema tooling does not yet generate outbox infrastructure
DDL; this demo emits no events. The event/outbox feature remains subject to its separate
language and persistence review. Query methods in repository declarations currently
produce an explicit unsupported-feature error rather than partially generated code.
