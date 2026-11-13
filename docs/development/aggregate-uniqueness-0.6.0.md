---
title: Aggregate field uniqueness — 0.6.0 design baseline
---
# Aggregate field uniqueness — 0.6.0 design baseline

Status: **accepted design baseline; partially implemented**. The
[language-model foundation](../contracts/aggregate-uniqueness.md) implements syntax,
effective names, semantic checks and module/editor metadata. The full example below
includes a repository and therefore cannot yet generate persistence: database
constraint/collation/migration/JDBC enforcement remains pending. Schema generation
rejects adoption instead of emitting an unenforced table. No identity/release gate is closed.

## Problem and boundary

A singleton `find Aggregate?` detects multiple matches after they exist; it cannot
prevent two writers from claiming the same login key. The
[credential policy](../contracts/saas-identity-credentials.md) requires exact canonical
login-key database uniqueness. The [pending lifetime](../contracts/saas-identity-registrations.md)
is implemented, but address ownership intentionally waits for this storage guarantee.

Use PostgreSQL's ordinary UNIQUE constraint and unique B-tree enforcement. Do not
introduce a process-local registry, a custom locking protocol, a hash-based substitute
for exact equality, or an existence query as the correctness mechanism. Existing
Spring transaction management and exception translation remain the technical foundation.
No SaaS dependency or email-specific keyword belongs in the compiler/runtime.

Uniqueness is a domain requirement across instances of an aggregate type. Declare
it on the aggregate field, where the model expresses that meaning. The repository
remains the explicit adoption of persistence; its generated database constraint
provides atomic enforcement. A VO's `validates` or one aggregate instance cannot
prove the cross-instance requirement, and `find` cardinality remains independent.
A constraint name is technical infrastructure, not a mandatory domain concept.

## Proposed declaration

The following complete model parses, but its repository adoption remains **blocked**
until database enforcement is implemented:

```text
namespace example.identity;

id LoginClaimId;
id UserId;
value LoginKey(String encoded);
value DeliveryAddress(String encoded);

global aggregate LoginClaim[LoginClaimId](
    UserId owner,
    unique LoginKey loginKey,
    DeliveryAddress deliveryAddress
);

repository LoginClaimRepository for LoginClaim {
    find LoginClaim? forKey(LoginKey key) where loginKey = :key;
}
```

`unique` is a modifier on the root domain field. It means that two persisted
LoginClaim instances cannot have the same loginKey within the aggregate's scope.
The declaration does not select the field's VO globally: another field of LoginKey
or another aggregate using LoginKey is unaffected. It does not make owner or
deliveryAddress unique. To require a second field to be independently unique, mark
that field separately. Two marked fields mean two independent constraints, never
one composite key.

The planned aggregate-field shape is:

```text
aggregateField := fieldModifiers type explicitOrDerivedFieldName
fieldModifiers := ('unique' | 'mut')*   // each modifier occurs at most once
```

This is a grammar sketch of the added modifier, not a replacement for the existing
field/type/name rules. Existing effective-name derivation remains authoritative.
Both `unique mut LoginKey loginKey` and `mut unique LoginKey loginKey` express the
same mutable unique field; the canonical documented spelling is `unique mut`.
Uniqueness does not imply immutability. A modify operation can change a marked field,
but persistence must reject a value already owned by another aggregate.
`unique` remains contextual: existing model, field and method identifiers spelled
`unique` remain legal wherever identifiers were legal. Duplicate modifiers are errors.

There is no repository-member syntax, user-supplied constraint name or naming override
in this first slice. Do not generate Java constraint classes, extra accessors, repository
methods or callable uniqueness validators. Ordinary factories/reconstitution remain
local object operations; constructing two equal keys in memory must not claim database
enforcement. A model without an adopted persistence provider retains the declarative
requirement, but no process-local enforcement is installed or promised. Any other future
provider must enforce the declared requirement or reject adoption explicitly.

The logical constraint identity is the aggregate identity plus the effective field
name. Renaming a repository, moving a source file, reordering fields or changing a
query must not rename the database constraint. Existing duplicate-effective-field
and one-persistence-repository-per-root rules remain in force. The uniqueness marker
must survive aggregate module publication/import as field-contract metadata, even
when the provider repository is private; it is not a separate named public type.

## Small first supported slice

Each modifier applies to one required aggregate-root domain field. Resolve it through the
shared typed model and authoritative storage plan, never by assuming the source name
is a physical column name. Accept:

- a Vernac ID;
- an enum value;
- an ordinary VO directly wrapping one required scalar of type String, UUID, boolean,
  byte, short, int or long, including the corresponding supported boxed forms.

Reject marked optional fields or optional wrapped scalars, entities, collections,
nested wrappers, multivalue VOs and other unsupported scalar types. The modifier is
not allowed on an entity, VO, event, parameter or metadata member; expressions, raw
SQL, nested paths and composite uniqueness declarations are not supported. These
exclusions are compiler diagnostics, not ignored declarations or generated Java errors. The root's built-in `id` already has
a primary key; adding a unique modifier to its identity slot is rejected.

This initial scope avoids inventing NULL semantics, flattening equivalence, decimal-scale,
floating signed-zero or temporal-component rules. Wider support requires an explicit
follow-up that preserves Java/query/storage equality. An enum constraint uses the same
persisted enum codes as the rest of the storage plan; code remapping must retain migration
review. There is no implicit lowercasing, Unicode normalization, trimming, plus-address
folding or provider alias handling. The canonical login key is prepared by the identity
boundary before persistence, independently of this general feature.

## Tenant scope and text equality

For a global root the constraint covers its selected field across the table. For a
tenant root it covers `(@tenantId, selected field)`. Tenant scoping is automatic and
mandatory; source never names the technical tenant column or overrides this to make a
tenant field globally unique. A global login claim therefore stays explicitly global.
Existing RLS, tenant pinning and root identity rules remain unchanged. Constraint errors
must not disclose a conflicting tenant or row.

Text equality is exact stored spelling: byte-distinct supported strings may coexist;
the same supported spelling conflicts. Do not inherit a nondeterministic/default locale
collation that merges case or accents. A text storage column selected by this feature
must explicitly use PostgreSQL `COLLATE "C"`, represented in the shared schema/storage
metadata rather than smuggled into a codec or inferred only in emitted SQL. Query
comparisons on that column consequently use the same deterministic column collation.
No other field inherits that collation because it uses the same VO type.

This column collation also governs text ordering for that field; that is an observable
mapping choice and must be documented in the implemented query contract. It does not
supply Java String comparison order for every Unicode spelling. Adding or removing the
unique declaration can change the selected column's collation and therefore needs an
explicit migration/review; do not preserve an undocumented history-dependent mapping.

PostgreSQL B-tree index tuple/row-size limits still apply. The feature does not make
unbounded TEXT indexable or silently truncate/hash a large value. Domain boundaries
must apply suitable bounds (the first login key is at most 254 ASCII bytes), and tests
must cover sanitized failure for unsupported oversized index entries. A second copy of
the raw key must not appear in diagnostics, logs or generated constraint names.

## Schema ownership and migrations

The ordinary generated schema, module-owned snapshots, initial DDL, incremental planner
and schema-check must all describe the same constraint and column collation. Use the
shared quoted physical-name mapping with a logical name of the form
`@unique:<AggregateName>:<EffectiveFieldName>`; validate collisions with existing constraints
and indexes after physical-name mapping. Do not embed values or source paths in names.

Represent column collation explicitly in the schema model. Read existing snapshot
formats with their prior semantics, write a versioned compatible extension, and include
collation in fingerprints, comparisons and live catalog checks. Old source histories
remain unchanged. Do not hand-edit snapshots to bypass unsupported source syntax.
Module packaging and activated consumers must retain ownership of the constraint;
importing a repository contract must not create a second table or constraint locally.

Adding the declaration produces an additive constraint candidate plus any explicit
column-collation alteration. Existing duplicate data makes application fail atomically;
never delete rows, choose an owner, merge identities or backfill synthetic keys to make
it succeed. Operators need a separate authorized data-resolution step before retry.
Renaming a marked effective field or aggregate changes its derived technical identity
and is an explicit schema change, not a Java-only refactor. The planner must surface
the relevant column/constraint changes for review. Do not infer a rename from matching
VO types, silently drop protection, or rewrite published history. Existing tooling
cannot infer renames; a data-preserving rename requires explicitly reviewed migration
SQL and the matching target schema. No new automatic rename support is implied.
A field-type change must also
revalidate storage equality and scope. Changing tenant classification remains an
explicit persistence-boundary change. Dropping the declaration drops its owned
constraint and restores the current ordinary field mapping through reviewed migration.
Reuse existing foreign-key/UNIQUE ordering and data-change safeguards in the planner.
Constraint creation/collation changes can scan data and acquire blocking locks; no
online/concurrent-index or zero-downtime migration guarantee is included.

This feature does not implicitly create foreign keys to the business key, create a
natural-key aggregate ID, release a login claim on closure, or supply a general index DSL.
It does not use deferrable constraints or partial/expression indexes in this first slice.

## Concurrent writes and safe failures

The database decides the winner. Independent instances inserting the same effective key
have at most one committed row. Updating an existing aggregate cannot steal another root's
key; a rolled-back failed update leaves persisted data and ownership unchanged. Saving the
same root without a key change remains valid. Application mutations are not automatically
repaired in memory by database rollback; reload before further work.

Use Spring's existing JDBC exception translation to identify integrity failures and
structured PostgreSQL constraint metadata to recognize a declared unique constraint.
Do not parse localized error text or inspect PostgreSQL's DETAIL value. Recognized
business-key conflicts become a narrow sanitized runtime exception carrying only the
allowlisted logical aggregate/field identities. Map the generated physical constraint
name back to those identities; no user-supplied constraint name is required. The
exception must contain no parameter, raw login key, conflicting row/tenant, SQL, nested driver cause or suppressed raw error.
There is no automatic public HTTP status mapping.

Only the matching declared constraint gets that classification. Primary-key, optimistic
lock, unrelated integrity, permission, connection and migration failures keep their own
existing categories and must not be mislabeled as an address conflict. The implementation
must mark its writer rollback-only before returning a sanitized conflict; catching it
inside an ambient transaction must not allow partial graph writes to commit. A field
conflict never silently becomes an upsert or successful save.

Identity orchestration will later translate a conflict into its neutral, transactionally
correct registration behavior. This general feature must not send mail, retry registration,
transfer claims or expose whether a public login address exists.

## Diagnostics and editor acceptance

Implement resolution/validation once in shared compiler analysis and consume its result
from schema planning, code generation, LSP and IntelliJ. Required located diagnostics
cover unsupported/optional field types, wrong declaration kind, duplicate modifiers,
unsupported composite/naming forms and mapped physical-name collisions. Existing
field-name diagnostics remain authoritative. Diagnostics identify declarations and
fields, never runtime values.

Native editing must recognize the contextual field modifier, recover incomplete
aggregate fields, offer unique/mut modifiers in valid positions and preserve normal
field/type completion and type navigation. Hover on a marked field explains its
global/tenant scope and database enforcement boundary. There is no separate constraint
name or selector reference to resolve. Unsaved LSP/compiler analysis must agree with
the build. Changes must preserve existing files that use `unique` as an identifier.
Packaged IntelliJ acceptance remains separate from headless tests.

## Implementation and acceptance plan

| Slice | Required proof |
| --- | --- |
| Compiler/editor model | Positive global/tenant fields; both modifier orders and mutability; effective-name derivation; independently marked fields; every rejection above; shared located diagnostics; legacy keyword identifiers; unsaved completion/navigation; no fabricated constraint APIs |
| Mapping and history | Stable names/fingerprints; explicit collation; old snapshot reading; deterministic regenerated schema; tenant qualification; enum codes; exported aggregate field metadata and module ownership; unchanged unrelated histories |
| Migration/runtime | Live initial/add/drop/change replay and schema-check including collation; duplicate-data failure without owner changes; shared Spring translation and rollback-only sanitized conflict |
| Concurrent PostgreSQL | Restricted runtime role and two contexts: insert/insert, conflicting update, rollback retry, unchanged same-root save, different-tenant coexistence and same-tenant conflict |
| Equality/privacy | Exact text/case/accent behavior under a database with a different default collation; oversized value failure; no secret values in exception graphs; nonmatching failures retain categories |
| Identity adoption | Canonical address ownership only after the above proofs; race/restart/cleanup/closure rules; no public-flow acceptance until protected delivery and provisioning exist |

The model foundation now has compiler/module/headless editor tests. Executable
schema/runtime and packaged IDE proofs still belong to the enforcement follow-up and must reject skipped database cases in CI/release
checks. Update the implemented contracts, specification, language/AI references, guided
example, FAQ and plugin What's New when the feature actually ships. Working persistence examples must not adopt the marker until its backend enforcement
is available. The standalone domain example in the model contract compiles without a repository.

The compiler/module language-model foundation is now implemented separately, with an
explicit persistence guard. The next PR must implement shared schema mapping,
collation/migrations, JDBC enforcement and the remaining editor behavior with the
required PostgreSQL proofs before persistence adoption is enabled. Canonical identity address
ownership follows it; protected mail, account/owner provisioning and public browser flows
remain subsequent work. No completion percentage increases merely because this design is
written or merged.
