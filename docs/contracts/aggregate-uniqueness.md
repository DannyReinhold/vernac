---
title: Unique aggregate fields and PostgreSQL enforcement
---
# Unique aggregate fields and PostgreSQL enforcement

Status: **implemented in 0.6.0-SNAPSHOT** in the compiler, ordinary generated
PostgreSQL persistence, migration tooling and headless editor analysis. Installed
IntelliJ/manual release acceptance remains separate. This is a general model/persistence
feature; canonical login-address ownership and public registration are subsequent
identity work. The [design baseline](../development/aggregate-uniqueness-0.6.0.md)
records the scope and implementation acceptance.

## Declaration and effective names

```vernac
namespace example.identity;

id LoginClaimId;
id UserId;
value LoginKey(String encoded);
value DeliveryAddress(String encoded);

global aggregate LoginClaim[LoginClaimId](
    UserId owner,
    unique LoginKey,
    DeliveryAddress deliveryAddress
);

repository LoginClaimRepository for LoginClaim {
    find LoginClaim? forKey(LoginKey key) where loginKey = :key;
}
```

`unique LoginKey` and `unique LoginKey loginKey` have the same effective field name,
`loginKey`, using the normal [name derivation](names-and-unicode.md). Qualified types
and acronym names follow the same rule. Existing duplicate-name and Java API collision
checks still apply. No explicit constraint name is required or accepted.

The marker describes a domain requirement across instances of this aggregate type.
It does not mark every use of LoginKey unique. Several marked fields describe separate
requirements, not one composite key. `unique mut LoginKey` and `mut unique LoginKey`
are equivalent; canonical spelling is `unique mut`. Uniqueness does not imply immutability.
Repeated modifiers are located errors.

## Scope and equality

A global root's field is unique across that root table. A tenant root's constraint
covers the technical tenant ID plus the field, allowing the same value in different
tenants while preventing duplicates within one tenant. Source never supplies the
technical tenant column. Existing RLS, ownership and transaction pinning still apply.

The marker performs no normalization, case folding or mailbox alias interpretation.
Creating two objects in memory with the same key is allowed; no global Java registry,
construction-time database query or extra constraint API is installed. Persistence
adoption through a repository generates the database constraint. A singleton find
remains an independent cardinality assertion, not the enforcement mechanism.

Text columns selected for uniqueness explicitly use PostgreSQL `pg_catalog.C` collation.
Equality is exact supported stored spelling, including case, accents and combining
forms. Other fields using the same VO retain their ordinary mapping. Column comparisons
and ordering inherit that collation; this is not a universal Java String ordering
promise. Enum constraints use the existing recorded enum codes. No text is truncated
or hashed to obtain uniqueness. Canonical email login keys are prepared separately by
the identity boundary; case-insensitive login is not a behavior of this feature.

## Initial supported field types

Mark only a required aggregate-root domain field of one of these types:

- a Vernac ID;
- an enum;
- an ordinary VO directly wrapping one required String, UUID, boolean, byte, short,
  int or long scalar, including supported boxed forms.

Optional fields/wrapped scalars, nested wrappers, multivalue VOs, entities, collections,
decimal/floating/temporal and other scalar mappings are rejected. Java primitive fields
remain invalid aggregate domain fields. The marker belongs only to aggregate fields,
not VO/entity/event fields, method/query/usecase inputs, results or injected dependencies.
Identity slots cannot carry a field modifier. Composite declarations are deferred.
`unique` remains a contextual identifier in existing namespace/type/member positions.

## Schema ownership and migration

The technical constraint name derives from aggregate and effective field name using
the shared quoted/length-safe identifier mapping: `@unique:<AggregateName>:<FieldName>`.
Moving a source file, renaming a repository, reordering fields or editing queries does
not rename it. Renaming a marked field or aggregate is an explicit migration change;
the planner does not infer a data-preserving rename or transfer ownership.

Explicit collation is represented in **schema format 4**, in both compiler and runtime
data. Old formats 1–3 retain their representation and fingerprints when unchanged.
New uniqueness adoption writes format 4; once reached, generation retains format 4
when the last marker is removed. No old snapshots are edited. Module packaging, runtime
fingerprints, activation validation and live schema-check include this metadata.

Adding/removing a marker generates the owned UNIQUE and explicit selected-column
collation changes together. Removal restores the ordinary default column collation.
Constraint changes can scan data and acquire blocking locks; no online/zero-downtime
migration is promised. Existing duplicates make constraint application fail. The
migration must run transactionally; never delete rows, merge users or invent owners
to make it succeed. Resolve existing data separately through an authorized operation.
Use the normal [migration review workflow](../tutorials/postgresql-migrations.md).

## Writer transactions and conflicts

PostgreSQL enforces uniqueness across independent contexts. A conflicting insert or
key update cannot steal another root's key. Saving an unchanged key on the same root
remains valid. Rollback restores persisted data, not already-mutated Java objects;
reload or create fresh objects before retrying. Deletion releases a key only as part
of its committed transaction; it does not define a login-address reuse policy.

Marked repositories require their own bound JDBC transaction. Reads may use a bound
read-only transaction; save/delete require a writer. An ambient transaction for another
DataSource or an autocommit connection is not a substitute.

Spring performs JDBC error classification. A recognized conflict additionally requires
SQLState 23505 and the exact generated schema/table/constraint metadata reported by
PostgreSQL. It becomes `org.vernac.runtime.persistence.UniqueConstraintViolationException`,
whose `aggregate()` and `field()` expose logical model identities only. Its message,
cause and suppressed exceptions contain no raw key, conflicting row/tenant, SQL or
PostgreSQL DETAIL. Raw driver errors are not attached. The writer is marked rollback-only;
catching this exception inside it cannot commit earlier partial writes.

Primary-key, optimistic-lock, unrelated integrity and permission failures are not
reclassified as declared-field conflicts; their existing categories remain in force.
PostgreSQL program/index-size limits (SQLState 54000) on a marked repository become
sanitized `DataAccessResourceFailureException`, never a uniqueness outcome. Normal
public adapter sanitization is still required for other infrastructure failures.
There is no automatic HTTP result or public registration/existence response mapping.

PostgreSQL B-tree tuple-size limits still apply to unique TEXT. A domain must bound
inputs appropriately (the first login-key policy allows at most 254 ASCII bytes).
Unbounded TEXT is not guaranteed indexable; oversized values fail technically rather
than being silently shortened or converted to a digest.

## Modules, tooling and evidence

Uniqueness remains part of **module-contracts-12** field metadata, JSON round trips,
source contract views, imported ASTs, fingerprints and compatibility diagnostics.
Use matching compiler/runtime artifacts and rebuild development producers/consumers.
Importing a module-owned aggregate does not create a duplicate local table or constraint.
Version-4 bundles retain their own reviewed migration history and verify on restart;
a mismatched column collation prevents startup even if the unique constraint still exists.

Headless tests cover ordinary omitted/explicit names, both modifier orders, invalid
representations, derived constraint/scope mapping, deterministic legacy/format-4 history,
initial/add/remove replay, duplicate-data rollback, schema/catalog and startup collation
drift, compiled generated repositories and independent Spring contexts using a restricted
PostgreSQL role. Insert/update races have one winner; failed/caught work rolls back.
Text tests use an ICU-default database and a nondeterministic ICU control collation.
These test fixtures require PostgreSQL with ICU support; there is no ICU requirement
for the generated C-collated production columns themselves.

LSP type completion/navigation, field-scope hover and aggregate-local modifier completion
cover unsaved source. Native type references share compiler analysis. Packaged IntelliJ
installation/UI acceptance remains a manual release proof; headless checks do not replace it.
