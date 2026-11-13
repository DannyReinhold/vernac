---
title: Repository uniqueness — proposed 0.6.0 persistence contract
---
# Repository uniqueness — proposed 0.6.0 persistence contract

Status: **review proposal; not implemented syntax or runtime functionality**.
Merging this proposal accepts the design baseline for a subsequent implementation
PR. It does not make the examples compile, install a constraint or close an identity
or release gate. The current [query contract](../contracts/repository-queries.md)
and [PostgreSQL mapping](../contracts/postgresql-persistence.md) remain authoritative
for implemented behavior.

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

Uniqueness is a cross-aggregate storage invariant. The declaration belongs in the
repository, which already opts a root into persistence. A VO's `validates` cannot
prove uniqueness, and `find` result cardinality must remain a separate promise.

## Proposed declaration

The following code is **planned**, not valid in the current parser:

```text
namespace example.identity;

id LoginClaimId;
value CanonicalLoginKey(String encoded);
value DeliveryAddress(String encoded);

global aggregate LoginClaim[LoginClaimId](CanonicalLoginKey loginKey,
                                         DeliveryAddress deliveryAddress);
repository LoginClaimRepository for LoginClaim {
    unique LoginKey(loginKey);
    find LoginClaim? forKey(CanonicalLoginKey key) where loginKey = :key;
}
```

The planned repository grammar adds a member alongside existing find declarations:

```text
repositoryMember := repositoryFindMethod | repositoryUniqueConstraint
repositoryUniqueConstraint := 'unique' constraintName '(' rootFieldName ')' ';'
```

`constraintName` and `rootFieldName` are simple Vernac identifiers using the existing
identifier rules. `unique` is a contextual repository-member keyword; existing model,
field and method names spelled `unique` remain legal wherever identifiers were legal.
Do not introduce named Java classes, repository methods or callable validation APIs
for the constraint name. Member order has no semantic effect.

The constraint's logical identity is the persisted aggregate identity plus its explicit
constraint name. Renaming a repository, moving its source file, reordering members or
changing a query must not rename the database constraint. Reusing a name within one
repository is an error. Two names for the same effective field/scope are also an error,
rather than two redundant indexes. Existing one-persistence-repository-per-root rules
remain in force. A constraint is not a new public type/module export.

## Small first supported slice

Each declaration names exactly one required root domain field. Resolve it through the
shared typed model and authoritative storage plan, never by assuming the source name
is a physical column name. Accept:

- a Vernac ID;
- an enum value;
- an ordinary VO directly wrapping one required scalar of type String, UUID, boolean,
  byte, short, int or long, including the corresponding supported boxed forms.

Reject optional roots or optional wrapped scalars, metadata/id aliases, unknown fields,
entities, collections, nested wrappers, multivalue VOs, nested paths, composite fields,
expressions, raw SQL and other scalar types. These exclusions are compiler diagnostics,
not ignored declarations or generated Java errors. The root's built-in `id` already has
a primary key; a redundant unique declaration for it is rejected.

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
`@unique:<AggregateName>:<ConstraintName>`; validate collisions with existing constraints
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
Changing the selected field, constraint name or tenant classification is an explicit
schema change, not a Java-only refactor. Dropping the declaration drops its owned
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
allowlisted logical aggregate/constraint identities. It must contain no parameter,
raw login key, conflicting row/tenant, SQL, nested driver cause or suppressed raw error.
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
cover unknown/unsupported/optional fields, wrong target kind, repeated constraint names,
redundant field constraints, unsupported member forms and mapped physical-name collisions.
Diagnostics identify declarations and fields, never runtime values.

Native editing must recognize the contextual keyword, recover an incomplete member,
complete valid root fields, navigate the field reference to the root declaration and
explain scope in hover. Constraint names have their own declaration identity, not a
fabricated reference to a same-spelled VO. Unsaved LSP/compiler analysis must agree with
the build. Changes must preserve existing files that use `unique` as an identifier.
Packaged IntelliJ acceptance remains separate from headless tests.

## Implementation and acceptance plan

| Slice | Required proof |
| --- | --- |
| Compiler/editor model | Positive global/tenant declarations; mixed member order; every rejection above; shared located diagnostics; legacy keyword identifiers; unsaved completion/navigation; no generated public constraint methods |
| Mapping and history | Stable names/fingerprints; explicit collation; old snapshot reading; deterministic regenerated schema; tenant qualification; enum codes; module ownership; unchanged unrelated histories |
| Migration/runtime | Live initial/add/drop/change replay and schema-check including collation; duplicate-data failure without owner changes; shared Spring translation and rollback-only sanitized conflict |
| Concurrent PostgreSQL | Restricted runtime role and two contexts: insert/insert, conflicting update, rollback retry, unchanged same-root save, different-tenant coexistence and same-tenant conflict |
| Equality/privacy | Exact text/case/accent behavior under a database with a different default collation; oversized value failure; no secret values in exception graphs; nonmatching failures retain categories |
| Identity adoption | Canonical address ownership only after the above proofs; race/restart/cleanup/closure rules; no public-flow acceptance until protected delivery and provisioning exist |

No new implementation tests are claimed by this design PR. Executable schema/runtime/IDE
proofs belong to the implementation PR and must reject skipped database cases in CI/release
checks. Update the implemented contracts, specification, language/AI references, guided
example, FAQ and plugin What's New when the feature actually ships. Until then all working
examples keep using the current grammar; planned syntax is clearly isolated here.

The next PR should implement the first vertical slice through compiler, shared schema,
JDBC and editor analysis with the required PostgreSQL proofs. Canonical identity address
ownership follows it; protected mail, account/owner provisioning and public browser flows
remain subsequent work. No completion percentage increases merely because this design is
written or merged.
