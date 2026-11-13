---
title: Aggregate field uniqueness — language-model foundation
---
# Aggregate field uniqueness — language-model foundation

Status: **partial implementation in 0.6.0-SNAPSHOT**. Aggregate-field syntax,
semantic checks, generated ordinary domain objects, module metadata and shared editor
analysis are implemented. Database constraints, collation mapping, migration support
and JDBC conflict handling are **not implemented yet**. Schema/JDBC generation for a
repository-backed aggregate with a marked field fails explicitly instead of silently
omitting enforcement. This does not complete login-address ownership or registration.

The accepted [design baseline](../development/aggregate-uniqueness-0.6.0.md) specifies
the remaining persistence and editor acceptance. Its database promises are not runtime
capabilities of this first slice.

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

A global root's intended scope is all instances of that aggregate; a tenant root's
scope is the current tenant. The marker performs no normalization, case folding or
alias interpretation. Creating two objects in memory with the same key is allowed;
no global Java registry or object-construction query is installed. Generated getters,
factories and behavior APIs retain their ordinary shape. The persistence provider
must eventually enforce the requirement atomically before persistence adoption works.

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

`unique` remains a contextual identifier in existing namespace/type/member positions;
it is not globally reserved. The same structural checks serve normal and tolerant
editor parsing, while field-type checks run through shared project analysis.

## Module and editor contract

Uniqueness is included in exported aggregate field contracts, JSON round trips,
source contract views, imported ASTs, compatibility fingerprints and change diagnostics.
The metadata contract is now **module-contracts-12**. Older development artifacts must
be rebuilt with their consumers; they are rejected rather than interpreted as if the
new field requirement did not exist. The persistence snapshot format/history is unchanged.

Existing type completion and navigation work after the marker, including omitted field
names. LSP hover on the marker/explicit field name explains scope and the current
persistence limitation. Native type-reference building and unsaved shared analysis retain
the marker and its effective name. Dedicated modifier completion and packaged IntelliJ
acceptance remain follow-up work; headless tests do not establish installed UI behavior.

## Persistence adoption is blocked in this slice

Adding a repository for the marked aggregate currently makes schema/JDBC generation
fail with a located diagnostic: enforcement of unique aggregate fields is not yet
implemented. Source analysis alone can still resolve the domain model and its queries;
it is not schema or runtime acceptance. Applications must not hand-edit a snapshot,
remove the marker or reach around the provider boundary to claim equivalent support.

The next slice must supply derived database constraints, explicit text collation and
versioned schema metadata, initial/incremental migration/catalog checks, sanitized Spring
conflict translation, transaction rollback and multi-context PostgreSQL proofs. Only
then can the identity module adopt exact canonical login-key ownership. No database
uniqueness proof is claimed by these compiler/editor tests.
